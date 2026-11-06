package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentDetailStore
import io.github.mangi.eta.agent.subagent.SubagentHistoryEntry
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubagentInspectorInteractionTest {
    @Test
    fun informationTogglesVisiblyAfterScrollAndStreamingDoesNotMoveSteps() = withFixture { fixture ->
        val steps = (0..11).map { index ->
            SubagentStepSnapshot(id = "$index", kind = SubagentStepSnapshot.KIND_TOOL,
                title = "步骤编号 $index", toolName = "read_file", status = SubagentStepSnapshot.STATUS_SUCCESS)
        }
        val snapshot = mutableStateOf(SubagentActivitySnapshot(subagentId = "child-scroll", detailId = "detail-scroll",
            steps = steps, prompt = "任务指令测试"))
        fixture.setContent {
            Box(Modifier.height(500.dp)) {
                SubagentInspectorContent(snapshot.value, running = true, failed = false, onOpenDetails = {})
            }
        }
        fixture.advance(10)
        fixture.scrollToIndex(5)
        fixture.advance(10)
        fixture.assertVisibleText("步骤编号 5")
        assertFalse(fixture.isVisibleText("步骤编号 0"))
        fixture.clickDescription("查看任务指令与统计说明")
        fixture.advance(20)
        fixture.assertVisibleText("任务信息")
        fixture.assertVisibleText("步骤编号 5")
        assertTrue(fixture.hasDescription("收起任务信息"))

        // The live inspector keeps only the newest twelve; the first item drops when a
        // new step arrives. Stable step IDs must keep the reader on the same item.
        val anchoredTop = fixture.topOfText("步骤编号 5")
        snapshot.value = snapshot.value.copy(steps = steps.drop(1) + SubagentStepSnapshot(
            id = "new", kind = SubagentStepSnapshot.KIND_TOOL, title = "新增流式步骤"))
        fixture.advance(10)
        fixture.assertVisibleText("步骤编号 5")
        assertEquals("streaming must preserve the visible step position", anchoredTop, fixture.topOfText("步骤编号 5"), 2f)
        fixture.clickDescription("收起任务信息")
        fixture.advance(20)
        assertFalse(fixture.hasText("任务信息"))
        assertTrue(fixture.hasDescription("查看任务指令与统计说明"))
        fixture.assertVisibleText("步骤编号 5")
    }

    @Test
    fun historyEntryWorksForRunningFailedCancelledAndEmptyDetailId() = withFixture { fixture ->
        val snapshot = mutableStateOf(SubagentActivitySnapshot(subagentId = "child-entry", detailId = ""))
        var opened = 0
        fixture.setContent {
            val current = snapshot.value
            SubagentInspectorContent(current, running = current.status == SubagentActivitySnapshot.STATUS_RUNNING,
                failed = current.status in setOf(SubagentActivitySnapshot.STATUS_FAILED, SubagentActivitySnapshot.STATUS_CANCELLED),
                onOpenDetails = { opened++ })
        }
        for (status in listOf(SubagentActivitySnapshot.STATUS_RUNNING, SubagentActivitySnapshot.STATUS_FAILED,
            SubagentActivitySnapshot.STATUS_CANCELLED)) {
            snapshot.value = snapshot.value.copy(status = status)
            fixture.advance(8)
            fixture.assertVisibleText("历史记录")
            fixture.clickText("历史记录")
        }
        assertEquals(3, opened)
    }

    @Test
    fun partialHistoryFailureIsVisibleEvenWhenSomeMessagesExist() = withFixture { fixture ->
        val id = "ui-history-partial"
        val store = SubagentDetailStore(fixture.activity)
        try {
            store.save(id, SubagentActivitySnapshot(subagentId = "fixture", detailId = id, historyIncomplete = true))
            putIndex(id, listOf(SubagentHistoryEntry("one", "已保存回复", "assistant", "success")))
            fixture.setContent { SubagentFullDetails(id, allowDelete = false) }
            fixture.awaitText("部分记录保存失败，历史可能不完整")
            fixture.assertVisibleText("已保存回复")
        } finally { runBlocking { store.clear(id) } }
    }

    @Test
    fun emptyIdAndMissingOrBrokenDataAreVisibleAndRetryable() = withFixture { fixture ->
        val id = mutableStateOf("")
        val broken = "ui-broken-history"
        val missing = "ui-missing-history"
        val context = fixture.activity
        val store = SubagentDetailStore(context)
        try {
            store.save(broken, SubagentActivitySnapshot(subagentId = "fixture", detailId = broken))
            putChunk(broken, "history", 0, "[")
            fixture.setContent { SubagentFullDetails(id.value, allowDelete = false) }
            fixture.awaitText("缺少任务详情标识，无法读取历史记录。")
            fixture.clickDescription("刷新任务详情")
            fixture.awaitText("缺少任务详情标识，无法读取历史记录。")

            id.value = missing
            fixture.awaitText("详情未保留、已丢失或已清理")
            id.value = broken
            fixture.awaitText("历史记录读取失败：")
            putChunk(broken, "history", 0, "[]")
            fixture.clickDescription("刷新任务详情")
            fixture.awaitText("旧版记录不完整，仅显示已保留材料")
            fixture.assertVisibleText("任务指令")
        } finally {
            runBlocking { store.clear(broken); store.clear(missing) }
        }
    }

    @Test
    fun historyDirectoryPagesBackRefreshAndReopenUseRetainedData() = withFixture { fixture ->
        val id = "ui-history-directory"
        val store = SubagentDetailStore(fixture.activity)
        val entries = listOf(
            SubagentHistoryEntry("one", "第一条回复", "assistant", "success"),
            SubagentHistoryEntry("missing", "正文已丢失", "tool", "failed"),
            SubagentHistoryEntry("", "标识已丢失", "tool", "failed"),
        )
        val show = mutableStateOf(true)
        try {
            store.save(id, SubagentActivitySnapshot(subagentId = "fixture", detailId = id, prompt = "保留指令"))
            putIndex(id, entries)
            putChunk(id, "history-one", 0, "第一页正文")
            putChunk(id, "history-one", 1, "第二页正文")
            fixture.setContent { if (show.value) SubagentFullDetails(id, allowDelete = false) }
            fixture.awaitText("第一条回复")
            fixture.assertBefore("第一条回复", "正文已丢失")
            assertFalse(fixture.hasText("第一页正文"))
            fixture.clickText("第一条回复")
            fixture.awaitText("第一页正文")
            fixture.clickDescription("下一页")
            fixture.awaitText("第二页正文")
            fixture.assertVisibleText("第 2 页")
            fixture.clickDescription("上一页")
            fixture.awaitText("第一页正文")
            val updated = entries + SubagentHistoryEntry("later", "运行中新记录", "assistant", "partial")
            putIndex(id, updated)
            putChunk(id, "history-one", 0, "第一页正文 · 已刷新")
            fixture.clickDescription("刷新任务详情")
            fixture.awaitText("第一页正文 · 已刷新")
            fixture.clickDescription("返回详情目录")
            fixture.awaitText("正文已丢失")
            fixture.clickText("正文已丢失")
            fixture.awaitText("本项正文未保留、已丢失或达到保留上限。")
            fixture.activity.onBackPressedDispatcher.onBackPressed()
            fixture.awaitText("正文已丢失")
            fixture.scrollToIndex(3)
            fixture.awaitText("标识已丢失")
            fixture.clickText("标识已丢失")
            fixture.awaitText("该条历史记录缺少标识，正文无法读取。")
            fixture.assertVisibleText("该条历史记录缺少标识，正文无法读取。")

            fixture.clickDescription("刷新任务详情")
            fixture.awaitText("标识已丢失")
            fixture.scrollToIndex(4)
            fixture.awaitText("运行中新记录")
            show.value = false
            fixture.advance(10)
            putIndex(id, updated + SubagentHistoryEntry("reopened", "重新打开的新记录", "retry", "failed"))
            show.value = true
            fixture.awaitText("第一条回复")
            fixture.scrollToIndex(5)
            fixture.awaitText("重新打开的新记录")
        } finally {
            runBlocking { store.clear(id) }
        }
    }

    private fun putIndex(id: String, entries: List<SubagentHistoryEntry>) =
        putChunk(id, "history", 0, Json.encodeToString(entries))

    private fun putChunk(id: String, field: String, index: Int, content: String) = runBlocking {
        val dao = EtaDatabase.get(org.robolectric.RuntimeEnvironment.getApplication()).conversationDao()
        dao.insertTextChunk(AgentTextChunkEntity(SubagentDetailStore.TABLE, id, field, index, content))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = Fixture(controller.get())
        try {
            block(fixture)
        } finally {
            try { fixture.close() } finally {
                controller.pause().stop().destroy()
                EtaDatabase.closeForTests()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    private class Fixture(val activity: ComponentActivity) {
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)

        fun setContent(content: @Composable () -> Unit) {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent { MiuixTheme { content() } }
            activity.setContentView(host)
        }

        fun advance(frames: Int = 1) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                val content = activity.findViewById<View>(android.R.id.content)
                content.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1792, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 822, 1792)
                root().measureAndLayoutForTest()
            }
        }

        fun awaitText(text: String) {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (System.nanoTime() < deadline) {
                advance()
                if (hasText(text)) { advance(12); return }
                Thread.sleep(5)
            }
            error("text not shown: $text")
        }

        fun hasText(text: String): Boolean = nodes().any { it.hasText(text) }
        fun hasDescription(description: String): Boolean = nodes().any { it.hasDescription(description) }
        fun assertVisibleText(text: String) {
            assertTrue("text not visible within its scroll viewport: $text", isVisibleText(text))
        }
        fun isVisibleText(text: String): Boolean = nodes().filter { it.hasText(text) }.any { node ->
            val bounds = node.boundsInRoot
            if (bounds.height <= 0 || bounds.top < 0 || bounds.bottom > 1792) return@any false
            var parent = node.parent
            while (parent != null) {
                if (parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    (bounds.top < parent.boundsInRoot.top || bounds.bottom > parent.boundsInRoot.bottom)) return@any false
                parent = parent.parent
            }
            true
        }
        fun topOfText(text: String): Float = nodes().first { it.hasText(text) }.boundsInRoot.top
        fun assertBefore(first: String, second: String) {
            val a = nodes().first { it.hasText(first) }.boundsInRoot
            val b = nodes().first { it.hasText(second) }.boundsInRoot
            assertTrue("history order must be preserved", a.top < b.top)
        }
        fun clickText(text: String) = click { it.hasText(text) }
        fun clickDescription(description: String) = click { it.hasDescription(description) }
        private fun click(matches: (SemanticsNode) -> Boolean) {
            val all = nodes()
            val target = all.firstOrNull(matches) ?: error("node not found")
            var node: SemanticsNode? = target
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            val clickable = requireNotNull(node) { "clickable node not found for target" }
            assertTrue(clickable.config[SemanticsActions.OnClick].action?.invoke() == true)
            advance(4)
        }
        fun scrollToIndex(index: Int) {
            val node = nodes().firstOrNull { it.config.contains(SemanticsActions.ScrollToIndex) }
                ?: error("scrollable list not found")
            assertTrue(node.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true)
        }
        private fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return visit(root().semanticsOwner.rootSemanticsNode)
        }
        private fun SemanticsNode.hasText(text: String) =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                (text.endsWith('：') && config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(text) } == true)
        private fun SemanticsNode.hasDescription(description: String) =
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("Compose root not found")
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun close() {
            try { host.disposeComposition() } finally { recomposer.cancel(); scope.cancel() }
        }
    }
}
