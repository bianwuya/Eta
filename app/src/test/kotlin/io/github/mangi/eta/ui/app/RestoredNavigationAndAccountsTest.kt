package io.github.mangi.eta.ui.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.pages.providers.ProviderAccountControls
import io.github.mangi.eta.ui.pages.providers.ProviderConfigDraft
import io.github.mangi.eta.ui.pages.providers.ProviderConnectionFields
import io.github.mangi.eta.ui.pages.providers.ProviderLoginState
import io.github.mangi.eta.ui.screens.subagent.SubagentSettingsScreen
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], application = EtaApp::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredNavigationAndAccountsTest(private val uiStyle: AppearanceUiStyle) {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun themes(): List<Array<AppearanceUiStyle>> = AppearanceUiStyle.entries.map { arrayOf(it) }
    }

    @Test
    fun subagentSettingsNavigatesToCompactionAndRequestStats() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = TestFixture(controller.get(), uiStyle)
        try {
            var compactionCount = 0
            var statsCount = 0
            fixture.setContent {
                SubagentSettingsScreen(
                    onBack = {},
                    onOpenCompaction = { compactionCount++ },
                    onOpenRequestStats = { statsCount++ },
                )
            }
            fixture.advance(4)
            fixture.capturePreview("subagent_settings_${uiStyle.name}")

            val compactionNode = fixture.findNodeByText("上下文压缩")
            assertNotNull("上下文压缩入口必须存在", compactionNode)
            fixture.clickNode(compactionNode!!)
            assertEquals("点击上下文压缩必须触发回调", 1, compactionCount)

            val statsNode = fixture.findNodeByText("子代理请求统计")
            assertNotNull("子代理请求统计入口必须存在", statsNode)
            fixture.clickNode(statsNode!!)
            assertEquals("点击子代理请求统计必须触发回调", 1, statsCount)
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun conversationRenameDialogButtonEnabledWhenInactiveAndDisabledWhenActiveRun() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = TestFixture(controller.get(), uiStyle)
        try {
            val baseConversation = ConversationSummaryUi(
                id = "conv-rename-test",
                title = "测试会话旧标题",
                preview = "preview text",
                timeLabel = "10:00",
                mode = ConversationModeUi.Chat,
                isActiveRun = false,
            )

            var regenerateCount = 0
            val active = mutableStateOf(false)
            fixture.setContent {
                ConversationRenameDialog(
                    conversation = baseConversation.copy(isActiveRun = active.value),
                    onConfirmRename = {},
                    onRegenerateTitle = { regenerateCount++ },
                    onDismissRequest = {},
                )
            }
            fixture.advance(4)
            fixture.capturePreview("rename_dialog_inactive_${uiStyle.name}")

            val inactiveRegen = fixture.findNodeByText("重新生成标题")
            assertNotNull("未运行时重新生成标题按钮必须存在", inactiveRegen)
            assertFalse(
                "未运行时重新生成标题按钮必须处于启用状态",
                generateSequence(inactiveRegen!!) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) },
            )
            fixture.clickNode(inactiveRegen)
            assertEquals("未运行时点击重新生成标题必须触发回调", 1, regenerateCount)

            active.value = true
            fixture.advance(4)
            fixture.capturePreview("rename_dialog_active_${uiStyle.name}")

            val activeRegen = fixture.findNodeByText("重新生成标题")
            assertNotNull("运行时重新生成标题按钮依然存在", activeRegen)
            assertTrue(
                "运行时重新生成标题按钮必须处于禁用状态",
                generateSequence(activeRegen!!) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) },
            )
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun providerAccountControlsPassesProviderIdAndInvokesAccountCallbacks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = TestFixture(controller.get(), uiStyle)
        try {
            var selectedTarget = ""
            var reloginTarget = ""
            var renameTarget = Pair("", "")
            var removeTarget = ""

            val account1 = CodexAccountView(
                accountId = "acc-primary",
                name = "首选账号",
                email = "primary@example.com",
                displayName = "Primary User",
                selected = true,
                needsReauthentication = false,
                planEnabled = true,
            )
            val account2 = CodexAccountView(
                accountId = "acc-secondary",
                name = "备用账号",
                email = "secondary@example.com",
                displayName = "Secondary User",
                selected = false,
                needsReauthentication = false,
                planEnabled = true,
            )
            val state = ProviderLoginState(
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
                account = ProviderAccountSummary(
                    email = account1.email,
                    displayName = account1.displayName,
                    needsReauthentication = false,
                    planEnabled = true,
                ),
                accounts = listOf(account1, account2),
            )

            fixture.setContent {
                ProviderAccountControls(
                    state = state,
                    authMode = ProviderAuthModes.OPENAI_CHATGPT,
                    enabled = true,
                    browserError = null,
                    onOpenBrowser = {},
                    onManageUsage = {},
                    onLogin = {},
                    onDisconnect = {},
                    onCancel = {},
                    onSelectAccount = { selectedTarget = it },
                    onReloginAccount = { reloginTarget = it },
                    onRenameAccount = { id, name -> renameTarget = id to name },
                    onRemoveAccount = { removeTarget = it },
                    providerId = "provider-integration-id",
                )
            }
            fixture.advance(4)
            fixture.capturePreview("provider_accounts_${uiStyle.name}")

            // 1. 验证顶栏“更多”操作与“重新登录”
            val moreButton = fixture.findNodeByText("更多")
            assertNotNull("顶栏必须包含「更多」操作入口", moreButton)
            fixture.clickNode(moreButton!!)
            fixture.advance(4)

            val reloginButton = fixture.findNodeByText("重新登录")
            assertNotNull("账号操作弹层必须包含「重新登录」按钮", reloginButton)
            fixture.clickNode(reloginButton!!)
            assertEquals("重新登录必须传出当前账号 ID", "acc-primary", reloginTarget)

            // 2. 验证“更多” -> “删除”确认弹层
            fixture.clickText("更多")
            fixture.advance(4)
            val deleteOption = fixture.findNodeByText("删除")
            assertNotNull("账号操作弹层必须包含「删除」选项", deleteOption)
            fixture.clickNode(deleteOption!!)
            fixture.advance(4)
            fixture.capturePreview("provider_delete_${uiStyle.name}")

            val confirmDelete = fixture.findDeleteConfirmation()
            assertNotNull("删除确认弹层必须包含确认按钮", confirmDelete)
            fixture.clickNode(confirmDelete!!)
            assertEquals("删除必须传出当前账号 ID", "acc-primary", removeTarget)

            // 3. 验证 ChatGPT 登录区的“切换账号”与多账号选择列表
            val switchAccountButton = fixture.findNodeByText("首选账号")
            assertNotNull("当前账号条目必须支持手动切换", switchAccountButton)
            fixture.clickNode(switchAccountButton!!)
            fixture.advance(4)

            val secondAccountNode = fixture.findNodeByText("备用账号")
            assertNotNull("多账号列表必须列出备用账号", secondAccountNode)
            fixture.clickNode(secondAccountNode!!)
            assertEquals("选择账号必须透传选中项 ID", "acc-secondary", selectedTarget)
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun providerConnectionFieldsPassesCallbacksAndProviderId() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = TestFixture(controller.get(), uiStyle)
        try {
            var selectedId = ""
            var reloginId = ""

            val account1 = CodexAccountView(
                accountId = "acc-first",
                name = "第一账号",
                email = "first@example.com",
                displayName = "First User",
                selected = true,
                needsReauthentication = false,
                planEnabled = true,
            )
            val account2 = CodexAccountView(
                accountId = "acc-second",
                name = "第二账号",
                email = "second@example.com",
                displayName = "Second User",
                selected = false,
                needsReauthentication = false,
                planEnabled = true,
            )
            val loginState = ProviderLoginState(
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
                account = ProviderAccountSummary(
                    email = account1.email,
                    displayName = account1.displayName,
                    needsReauthentication = false,
                    planEnabled = true,
                ),
                accounts = listOf(account1, account2),
            )

            val providerSetting = OpenAiCompatibleProviderSetting(
                id = "provider-pipeline-test",
                name = "Pipeline Provider",
                baseUrl = "https://api.openai.com/v1",
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
            )
            val draft = ProviderConfigDraft.from(providerSetting)

            fixture.setContent {
                ProviderConnectionFields(
                    provider = providerSetting,
                    draft = draft,
                    onDraftChange = {},
                    loginState = loginState,
                    enabled = true,
                    browserError = null,
                    onOpenBrowser = {},
                    onManageUsage = {},
                    onLogin = {},
                    onDisconnect = {},
                    onCancel = {},
                    onSelectAccount = { selectedId = it },
                    onReloginAccount = { reloginId = it },
                    onRenameAccount = { _, _ -> },
                    onRemoveAccount = {},
                )
            }
            fixture.advance(4)
            fixture.capturePreview("provider_fields_${uiStyle.name}")

            // 点击“更多” -> “重新登录”，验证回调自 ProviderConnectionFields 完整穿透
            fixture.clickText("更多")
            fixture.advance(4)
            fixture.clickText("重新登录")
            assertEquals("ProviderConnectionFields 必须透传 onReloginAccount", "acc-first", reloginId)

            // 点击“切换账号” -> 选择第二账号，验证回调完整穿透
            fixture.clickText("第一账号")
            fixture.advance(4)
            fixture.clickText("第二账号")
            assertEquals("ProviderConnectionFields 必须透传 onSelectAccount", "acc-second", selectedId)
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class TestFixture(
        private val activity: ComponentActivity,
        private val uiStyle: AppearanceUiStyle,
    ) {
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)

        fun setContent(content: @Composable () -> Unit) {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = uiStyle, blurEnabled = false),
                    applyInterfaceScale = false,
                ) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) {
                        Scaffold { Column { content() } }
                    }
                }
            }
            activity.setContentView(host)
        }

        fun advance(frames: Int = 2) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                val content = activity.findViewById<View>(android.R.id.content)
                if (content != null) {
                    content.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
                    )
                    content.layout(0, 0, 1080, 2400)
                }
                ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { dialog ->
                    dialog.window?.decorView?.let { decor ->
                        decor.measure(
                            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
                        )
                        decor.layout(0, 0, 1080, 2400)
                        measureCompose(decor)
                    }
                }
                measureCompose(host)
            }
        }

        private fun measureCompose(view: View) {
            if (view is ViewRootForTest) view.measureAndLayoutForTest()
            if (view is ViewGroup) for (i in 0 until view.childCount) measureCompose(view.getChildAt(i))
        }

        fun nodes(): List<SemanticsNode> {
            val allViews = mutableListOf<View>()
            activity.window?.decorView?.let { allViews.add(it) }
            ShadowDialog.getShownDialogs().forEach { d ->
                if (d.isShowing) {
                    d.window?.decorView?.let { allViews.add(it) }
                }
            }
            val result = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                result.add(node)
                node.children.forEach(::visit)
            }
            fun scan(view: View) {
                if (view is ViewRootForTest) {
                    visit(view.semanticsOwner.unmergedRootSemanticsNode)
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        scan(view.getChildAt(i))
                    }
                }
            }
            allViews.reversed().forEach(::scan)
            return result
        }

        fun findNodeByText(text: String): SemanticsNode? {
            val matching = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
            }
            return matching.lastOrNull { node ->
                generateSequence(node) { it.parent }.any { it.config.contains(SemanticsActions.OnClick) }
            } ?: matching.lastOrNull()
        }

        fun clickNode(node: SemanticsNode) {
            var curr: SemanticsNode? = node
            while (curr != null && !curr.config.contains(SemanticsActions.OnClick)) {
                curr = curr.parent
            }
            val clickable = requireNotNull(curr) { "Clickable node not found" }
            val action = clickable.config[SemanticsActions.OnClick].action
            assertTrue(action?.invoke() == true)
            advance(30)
        }

        fun clickText(text: String) {
            val node = requireNotNull(findNodeByText(text)) { "Node with text '$text' not found" }
            clickNode(node)
        }
        fun findDeleteConfirmation(): SemanticsNode? {
            val candidates = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "删除" } == true
            }
            return if (uiStyle == AppearanceUiStyle.COUI) candidates.minByOrNull { it.boundsInRoot.top }
            else candidates.lastOrNull()
        }

        fun capturePreview(name: String) {
                advance(30)
                val dialogs = ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }
                val decor = dialogs.lastOrNull() ?: host
                val width = decor.width.takeIf { it > 0 } ?: 1080
                val height = decor.height.takeIf { it > 0 } ?: 2400
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                decor.draw(canvas)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                assertTrue("preview must contain rendered text and controls", pixels.toSet().size > 16)
                val dir = File("build/visual-preview")
                check(dir.mkdirs() || dir.isDirectory)
                File(dir, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
        }

        fun close() {
            try {
                host.disposeComposition()
            } finally {
                recomposer.cancel()
                scope.cancel()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
}
