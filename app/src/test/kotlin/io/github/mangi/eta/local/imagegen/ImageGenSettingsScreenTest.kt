package io.github.mangi.eta.local.imagegen

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], application = EtaApp::class, qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenSettingsScreenTest(private val style: AppearanceUiStyle) {
    companion object {
        @JvmStatic @Parameters(name = "{0}")
        fun themes(): List<Array<AppearanceUiStyle>> = AppearanceUiStyle.entries.map { arrayOf(it) }
    }

    @Test fun credentialKeyFailureKeepsSettingsPageOpenAndConfigurationIntact() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val config = ImageGenConfig(ImageGenSource.CODEX_SUBSCRIPTION, "fixed-subscription", "fixed-account")
        ImageGenConfigStore.write(activity, config)
        val frames = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(scope.coroutineContext)
        val host = ComposeView(activity)
        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = style, blurEnabled = false), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) {
                        ImageGenSettingsScreen(onBack = {}, readAccounts = { _, _ ->
                            throw ProviderAuthException("credential_key_unavailable", "登录凭据的密钥不可用，请退出后重新登录")
                        })
                    }
                }
            }
            activity.setContentView(host)
            fun nodes(view: View): List<SemanticsNode> {
                fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
                return when (view) {
                    is ViewRootForTest -> visit(view.semanticsOwner.unmergedRootSemanticsNode)
                    is ViewGroup -> (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
                    else -> emptyList()
                }
            }
            var texts = emptyList<String>()
            repeat(80) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                host.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 720, 1800)
                texts = nodes(host).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
                if (texts.any { it.contains("订阅账号凭据不可用") }) return@repeat
                Thread.sleep(5)
            }
            assertTrue("Expected login error on the settings page: $texts", texts.any { it.contains("订阅账号凭据不可用") })
            assertTrue(texts.any { it.contains("生图来源") })
            assertEquals(config, ImageGenConfigStore.read(activity))
        } finally {
            host.disposeComposition()
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }
}
