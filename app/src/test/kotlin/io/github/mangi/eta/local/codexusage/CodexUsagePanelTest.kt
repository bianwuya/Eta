package io.github.mangi.eta.local.codexusage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.io.File
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import com.itos.coui.effect.LocalCouiContourGlowEnabled

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexUsagePanelTest(
    private val style: AppearanceUiStyle,
    private val dark: Boolean,
    private val widthDp: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic @Parameters(name = "{0}-dark={1}-{2}dp-font={3}")
        fun configurations(): List<Array<Any>> = AppearanceUiStyle.entries.flatMap { style ->
            listOf(false, true).flatMap { dark ->
                listOf(arrayOf<Any>(style, dark, 360, 1f), arrayOf<Any>(style, dark, 320, 1.6f))
            }
        }
    }

    @Test fun compactSummaryExpandsToWindowsAndExplicitResetWithoutTextOverflow() = withFixture { f ->
        f.render()
        f.advance()
        assertTrue(f.hasText("20.0% · codex · 7天"))
        assertFalse(f.hasText("重置额度"))
        assertFalse(f.hasText("可用重置机会：2 次"))
        f.capture("compact")
        f.clickDescription("刷新额度")
        assertEquals(1, f.refreshes)
        f.clickDescription("展开额度详情")
        assertTrue(f.hasText("可用重置机会：2 次"))
        assertTrue(f.hasText("剩余 80.0%"))
        assertTrue(f.hasText("剩余 20.0%"))
        f.assertActionsShareRow()
        f.clickText("重置额度")
        assertEquals(1, f.resets)
        assertTrue("reset is a command, so details remain expanded", f.hasText("可用重置机会：2 次"))
        f.assertTextFits()
        f.capture("expanded")
        f.clickDescription("收起额度详情")
        assertFalse(f.hasText("重置额度"))
    }

    @Test fun pendingOperationAndUnknownCountHaveAccurateDisabledStates() = withFixture { f ->
        f.expanded.value = true
        f.available.value = null
        f.allowed.value = false
        f.render()
        f.advance()
        assertTrue(f.hasText("可用重置机会：未知"))
        assertTrue(f.clickableText("重置额度").config.contains(SemanticsProperties.Disabled))
        f.pending.value = true
        f.allowed.value = true
        f.advance()
        assertTrue(f.hasText("上次重置结果待确认"))
        f.clickText("核对本次重置")
        assertEquals(1, f.resets)
        f.assertTextFits()
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val f = Fixture(activity.get())
        try { block(f) } finally {
            f.close()
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private inner class Fixture(val activity: ComponentActivity) {
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)
        val expanded = mutableStateOf(false)
        val pending = mutableStateOf(false)
        val available = mutableStateOf<Int?>(2)
        val allowed = mutableStateOf(true)
        var refreshes = 0
        var resets = 0
        fun render() {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = style, themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    blurEnabled = false), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale),
                        LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                            CodexUsageContent(
                                snapshot = CodexUsage(listOf(UsageWindow("codex", 80.0, 18000, 2000000000L),
                                    UsageWindow("codex", 20.0, 604800, 2000500000L)), 1794320000000, available.value),
                                expanded = expanded.value, nowSeconds = 1999999000L, loading = false, resetting = false,
                                pending = pending.value, fresh = true, usable = true, cacheLoaded = true,
                                canReset = allowed.value, canRefresh = true, error = null, notice = null, canManage = true,
                                onToggleDetails = { expanded.value = !expanded.value }, onRefresh = { refreshes++ },
                                onReset = { resets++ }, onManageUsage = {},
                            )
                        }
                    }
                }
            }
            activity.setContentView(host)
        }
        fun advance() {
            repeat(15) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                host.measure(View.MeasureSpec.makeMeasureSpec(widthDp * 2, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, widthDp * 2, 1800)
                root().measureAndLayoutForTest()
            }
        }
        private fun root(): ViewRootForTest {
            fun find(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            return requireNotNull(find(host))
        }
        private fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return visit(root().semanticsOwner.unmergedRootSemanticsNode)
        }
        private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun hasText(text: String) = nodes().any { it.text() == text }
        private fun clickable(node: SemanticsNode): SemanticsNode =
            if (node.config.contains(SemanticsActions.OnClick)) node else clickable(requireNotNull(node.parent))
        fun clickableText(text: String) = clickable(nodes().first { it.text() == text })
        fun clickText(text: String) { assertTrue(clickableText(text).config[SemanticsActions.OnClick].action?.invoke() == true); advance() }
        fun clickDescription(text: String) {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }
            assertTrue(clickable(node).config[SemanticsActions.OnClick].action?.invoke() == true)
            advance()
        }
        fun assertTextFits() {
            nodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                for (result in results) {
                    for (line in 0 until result.lineCount) {
                        assertTrue("text width clipped: ${result.layoutInput.text}, line=$line, right=${result.getLineRight(line)}, size=${result.size}",
                            result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
                    }
                    assertTrue("text height clipped: ${result.layoutInput.text}, size=${result.size}, paragraph=${result.multiParagraph.height}",
                        result.multiParagraph.height <= result.size.height + 1f && !result.multiParagraph.didExceedMaxLines)
                    assertTrue("text outside width: ${node.boundsInRoot}", node.boundsInRoot.right <= widthDp * 2 + 1)
                }
            }
        }
        fun assertActionsShareRow() {
            val reset = clickableText("重置额度").boundsInRoot
            val manage = clickable(nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("管理用量") == true
            }).boundsInRoot
            assertTrue("usage actions must be side by side: reset=$reset manage=$manage", reset.right <= manage.left)
            assertTrue("usage actions must share a row: reset=$reset manage=$manage", reset.top < manage.bottom && manage.top < reset.bottom)
            if (fontScale == 1f) assertTrue("secondary button must stay compact: $reset", reset.height <= 80f)
        }
        fun capture(state: String) {
            val bitmap = Bitmap.createBitmap(widthDp * 2, 1800, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("render must include text and controls", pixels.toSet().size > 16)
            val file = File("build/visual-preview/usage-${style.name}-${if (dark) "dark" else "light"}-$widthDp-$fontScale-$state.png")
            val directory = requireNotNull(file.parentFile)
            check(directory.mkdirs() || directory.isDirectory)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun close() { host.disposeComposition(); recomposer.cancel(); scope.cancel() }
    }
}
