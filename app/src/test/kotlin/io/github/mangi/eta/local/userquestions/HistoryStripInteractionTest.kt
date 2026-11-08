package io.github.mangi.eta.local.userquestions

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryStripInteractionTest {
    @Test fun activeQuestionTabsOptionsAndLargeTextKeepSemanticSelection() = withFixture { f ->
        val (id, waiter) = f.active()
        f.render(fontScale = 1.4f, maxHeight = 520.dp)
        f.awaitText("1 · 策略")
        f.awaitText("可多选，也可以直接填写")
        assertTrue(f.nodes().any { it.text().contains("选项说明可折行并保留说明层级") })

        val optionA = f.nodes().first { it.text().contains("保留手动标题") }
        assertEquals(Role.Checkbox, f.clickable(optionA).config.getOrNull(SemanticsProperties.Role))
        f.clickContains("保留手动标题")
        assertEquals(ToggleableState.On, f.clickable(f.nodes().first { it.text().contains("保留手动标题") })
            .config.getOrNull(SemanticsProperties.ToggleableState))
        f.clickContains("失败不影响聊天")
        f.awaitDraftCount(id, 2)
        assertEquals(2, UserQuestionStore.forms.value.single { it.id == id }.drafts[0].selected.size)

        f.clickText("2 · 外观")
        f.awaitText("先保留现有主题")
        val optionB = f.nodes().first { it.text().contains("先保留现有主题") }
        assertEquals(Role.RadioButton, f.clickable(optionB).config.getOrNull(SemanticsProperties.Role))
        f.clickContains("先保留现有主题")
        assertTrue(f.clickable(f.nodes().first { it.text().contains("先保留现有主题") })
            .config.getOrNull(SemanticsProperties.Selected) == true)
        f.clickContains("1 · 策略")
        assertEquals(ToggleableState.On, f.clickable(f.nodes().first { it.text().contains("保留手动标题") })
            .config.getOrNull(SemanticsProperties.ToggleableState))
        assertFalse(waiter.isDone)
        assertTrue(UserQuestionStore.setPresentation(id, UserQuestionPresentation.HIDDEN))
        assertTrue(UserQuestionStore.setPresentation(id, UserQuestionPresentation.EXPANDED))
        assertEquals(2, UserQuestionStore.forms.value.single { it.id == id }.drafts[0].selected.size)
    }

    @Test fun historyViewCollapseAndCloseRemainReadOnlyAfterTimeout() = withFixture { f ->
        val (id, waiter) = f.expired()
        f.render()
        f.awaitText("历史提问")
        assertTrue(f.hasText("等待回答超时 · 原提问已过期"))
        val before = UserQuestionStore.forms.value.single { it.id == id }
        val result = waiter.getNow("missing")
        f.clickText("查看")
        assertTrue(f.hasText("收起"))
        assertTrue(f.nodes().any { it.text().contains("示例问题") })
        assertFalse(f.nodes().any { it.config.contains(SemanticsActions.SetText) })
        f.clickText("收起")
        assertTrue(f.hasText("查看"))
        assertFalse(f.nodes().any { it.text().contains("示例问题") })
        f.clickDescription("关闭历史提示")
        assertFalse(f.hasText("历史提问"))
        assertEquals(before, UserQuestionStore.forms.value.single { it.id == id })
        assertEquals(result, waiter.getNow("missing"))
        assertTrue(waiter.isDone)
    }

    @Test fun narrowLargeFontKeepsStatusAndIndependentActionsWithinBounds() = withFixture { f ->
        f.expired()
        f.render(fontScale = 1.8f)
        f.awaitText("历史提问")
        val title = f.nodes().first { it.text() == "历史提问" }
        val status = f.nodes().first { it.text() == "等待回答超时 · 原提问已过期" }
        assertTrue("status must be below the title, not squeezed beside the buttons", status.boundsInRoot.top >= title.boundsInRoot.bottom)
        for (node in f.nodes().filter { it.text() in setOf("历史提问", "查看", "等待回答超时 · 原提问已过期") }) {
            assertTrue("text must retain width: ${node.boundsInRoot}", node.boundsInRoot.width > 0f)
            assertTrue("text must stay in viewport: ${node.boundsInRoot}", node.boundsInRoot.right <= f.activity.resources.displayMetrics.widthPixels)
        }
        val view = f.clickable(f.nodes().first { it.text() == "查看" }).boundsInRoot
        val close = f.clickable(f.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭历史提示") == true }).boundsInRoot
        assertTrue("view and close touch regions must not overlap", view.right <= close.left)
        assertTrue(view.width >= 48f * f.activity.resources.displayMetrics.density)
        assertTrue(close.width >= 48f * f.activity.resources.displayMetrics.density)
        f.clickText("查看")
        assertTrue(f.hasText("收起"))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        UserQuestionStore.resetForTest()
        val f = Fixture(controller.get())
        try { block(f) } finally {
            try { f.close() } finally {
                UserQuestionStore.resetForTest()
                controller.pause().stop().destroy()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    private class Fixture(val activity: ComponentActivity) {
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)
        private val conversation = "history-ui-${System.nanoTime()}"

        fun expired(): Pair<String, java.util.concurrent.CompletableFuture<String>> {
            UserQuestionStore.initialize(activity)
            val pair = UserQuestionStore.open(conversation, "run-$conversation", "call", JSONObject("""{"questions":[{"prompt":"示例问题"}]}"""))
            UserQuestionStore.dismiss(pair.first, "等待回答超时 · 原提问已过期", expired = true)
            return pair
        }
        fun active(): Pair<String, java.util.concurrent.CompletableFuture<String>> {
            UserQuestionStore.initialize(activity)
            val pair = UserQuestionStore.open(conversation, "run-$conversation", "call", JSONObject("""{"questions":[{"prompt":"本轮策略","tab":"策略","multiple":true,"options":[{"label":"保留手动标题","description":"首轮完成后不会覆盖已手动设置的标题"},{"label":"失败不影响聊天","description":"选项说明可折行并保留说明层级"}]},{"prompt":"设置外观","tab":"外观","options":[{"label":"先保留现有主题"},{"label":"沿相邻组件对齐"}]}]}"""))
            check(UserQuestionStore.setPresentation(pair.first, UserQuestionPresentation.EXPANDED))
            return pair
        }
        fun render(fontScale: Float = 1f, maxHeight: androidx.compose.ui.unit.Dp = 220.dp) {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                MiuixTheme {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Box(Modifier.width(300.dp)) { UserQuestionsPanel(conversation, maxHeight = maxHeight) }
                    }
                }
            }
            activity.setContentView(host)
        }
        fun advance(times: Int = 8) {
            repeat(times) {
                Snapshot.sendApplyNotifications()
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                activity.window.decorView.measure(
                    View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels, View.MeasureSpec.EXACTLY))
                activity.window.decorView.layout(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            }
        }
        fun awaitText(text: String) {
            repeat(120) { advance(2); if (hasText(text)) return }
            error("text not found: $text; ${nodes().map { it.text() }}")
        }
        fun awaitDraftCount(id: String, count: Int) {
            repeat(80) {
                advance(2)
                if (UserQuestionStore.forms.value.singleOrNull { it.id == id }?.drafts?.firstOrNull()?.selected?.size == count) return
            }
            error("draft selection did not persist: ${UserQuestionStore.forms.value.singleOrNull { it.id == id }?.drafts}")
        }
        fun hasText(text: String) = nodes().any { it.text() == text }
        fun clickText(text: String) = click(nodes().first { it.text() == text })
        fun clickContains(text: String) = click(nodes().first { it.text().contains(text) })
        fun clickDescription(text: String) = click(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true })
        fun clickable(target: SemanticsNode): SemanticsNode {
            var node: SemanticsNode? = target
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            return requireNotNull(node)
        }
        private fun click(target: SemanticsNode) {
            assertTrue(clickable(target).config[SemanticsActions.OnClick].action?.invoke() == true)
            advance()
        }
        fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return visit(requireNotNull(findRoot(activity.window.decorView)).semanticsOwner.rootSemanticsNode)
        }
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
            return null
        }
        fun close() { try { host.disposeComposition() } finally { recomposer.cancel(); scope.cancel() } }
    }
}

private fun SemanticsNode.text(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
