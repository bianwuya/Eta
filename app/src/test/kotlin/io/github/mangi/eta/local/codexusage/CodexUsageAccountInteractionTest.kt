package io.github.mangi.eta.local.codexusage

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.testing.CodexUsageFixture
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
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

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexUsageAccountInteractionTest(private val style: AppearanceUiStyle) {
    companion object {
        @JvmStatic @Parameters(name = "{0}")
        fun themes(): List<Array<AppearanceUiStyle>> = AppearanceUiStyle.entries.map { arrayOf(it) }
    }

    @Test fun switchingBeforeConfirmationClosesDialogWithoutConsuming() = withPanel { ui, accounts ->
        val posts = AtomicInteger()
        val client = client { method, _ -> if (method == "POST") { posts.incrementAndGet(); """{"code":"reset","windows_reset":[]}""" } else usage(50) }
        withClient(client) {
            val selected = mutableStateOf(accounts.first)
            ui.render { CodexUsagePanel(accounts.providerId, selected.value, true, true, true, {}, "账号 ${selected.value}") }
            ui.awaitText("50.0% · codex · 5小时")
            ui.click("展开额度详情")
            ui.click("重置额度")
            ui.awaitText("确认重置")
            selected.value = accounts.second
            ui.advance()
            ui.awaitText("50.0% · codex · 5小时")
            assertFalse(ui.hasText("确认重置"))
            assertEquals(0, posts.get())
        }
    }

    @Test fun doubleClickAndAccountSwitchKeepRedemptionAndResultsWithOriginalAccount() = withPanel { ui, accounts ->
        val posts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRefreshed = AtomicInteger()
        var postAccount: String? = null
        val client = client { method, workspace ->
            if (method == "POST") {
                posts.incrementAndGet()
                postAccount = workspace
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                firstRefreshed.set(1)
                """{"code":"reset","windows_reset":["primary"]}"""
            } else usage(if (workspace == "workspace-second") 80 else if (firstRefreshed.get() == 1) 25 else 50)
        }
        withClient(client) {
            try {
                val selected = mutableStateOf(accounts.first)
                ui.render { CodexUsagePanel(accounts.providerId, selected.value, true, true, true, {}, "账号 ${selected.value}") }
                ui.awaitText("50.0% · codex · 5小时")
                ui.click("展开额度详情")
                ui.click("重置额度")
                ui.awaitText("确认重置")
                val confirm = ui.clickAction("确认重置")
                assertTrue(confirm.invoke())
                confirm.invoke()
                ui.await { entered.count == 0L }
                selected.value = accounts.second
                ui.awaitText("20.0% · codex · 5小时")
                release.countDown()
                ui.await { !CodexResetRepository.isInFlight(accounts.providerId, accounts.first) }
                ui.advance()
                assertTrue(ui.hasText("20.0% · codex · 5小时"))
                assertEquals("workspace-first", postAccount)
                assertEquals(1, posts.get())
                assertFalse(CodexResetRepository.hasPendingConfirmation(ui.activity, accounts.providerId, accounts.first))
                selected.value = accounts.first
                ui.awaitText("75.0% · codex · 5小时")
            } finally { release.countDown() }
        }
    }

    private fun usage(used: Int) = """{"rate_limit":{"primary_window":{"used_percent":$used,"limit_window_seconds":18000,"reset_at":2000000000}},"rate_limit_reset_credits":{"available_count":2}}"""
    private fun client(respond: (String, String?) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(request.method, request.header("ChatGPT-Account-Id")).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun withClient(client: OkHttpClient, block: () -> Unit) {
        val originalUsage = CodexUsageRepository.httpClientProvider
        val originalReset = CodexResetRepository.httpClientProvider
        CodexUsageRepository.httpClientProvider = { client }
        CodexResetRepository.httpClientProvider = { client }
        try { block() } finally {
            CodexUsageRepository.httpClientProvider = originalUsage
            CodexResetRepository.httpClientProvider = originalReset
        }
    }
    private fun withPanel(block: (Panel, CodexUsageFixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val ui = Panel(controller.get())
        try { CodexUsageFixture(ui.activity, "ui-reset-${System.nanoTime()}").use { block(ui, it) } }
        finally { ui.close(); controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private inner class Panel(val activity: ComponentActivity) {
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)
        fun render(content: @Composable () -> Unit) {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = style, blurEnabled = false), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
            activity.setContentView(host)
        }
        fun advance() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                val views = listOf(host) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }
                for (view in views) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 720, 1800)
                }
            }
        }
        private fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            fun scan(view: View): List<SemanticsNode> = when (view) {
                is ViewRootForTest -> visit(view.semanticsOwner.unmergedRootSemanticsNode)
                is ViewGroup -> (0 until view.childCount).flatMap { scan(view.getChildAt(it)) }
                else -> emptyList()
            }
            return (listOf(host) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }).flatMap(::scan)
        }
        private fun SemanticsNode.matches(text: String) =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        fun hasText(text: String) = nodes().any { it.matches(text) }
        fun clickAction(text: String): () -> Boolean {
            var node = nodes().first { it.matches(text) }
            while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
            return requireNotNull(node.config[SemanticsActions.OnClick].action)
        }
        fun click(text: String) { assertTrue(clickAction(text).invoke()); advance() }
        fun awaitText(text: String) = await { hasText(text) }
        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < deadline) { advance(); if (predicate()) return; Thread.sleep(5) }
            assertTrue("UI condition not met", predicate())
        }
        fun close() { host.disposeComposition(); recomposer.cancel(); scope.cancel() }
    }
}
