package io.github.mangi.eta.officialpreview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/** Draws and drives production Compose with native Robolectric Canvas; no emulator/device claim. */
internal class ProductionUiHarness(val style: AppearanceUiStyle, dark: Boolean = false,
    private val width: Int = 720, private val height: Int = 1800, fontScale: Float = 1f,
    appearanceStyle: State<AppearanceUiStyle>? = null,
    content: @Composable () -> Unit) : AutoCloseable {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    val host = ComposeView(activity)
    private val renderBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val renderCanvas = Canvas(renderBuffer)
    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        host.setParentCompositionContext(recomposer)
        host.setContent {
            AgentAppTheme(AppearanceSettings(uiStyle = appearanceStyle?.value ?: style, blurEnabled = false,
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                    LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) { content() }
            }
        }
        activity.setContentView(host)
        advance(35)
    }
    fun frame() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        // Compose schedules draw-time observations (including NavDisplay visibility) in draw.
        // Measuring without drawing can leave a navigated screen's semantics stale.
        host.draw(renderCanvas)
    }
    fun advance(count: Int = 35) { repeat(count) { frame() } }
    fun nodes(): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        fun root(view: View): List<SemanticsNode> = when (view) {
            is ViewRootForTest -> visit(view.semanticsOwner.unmergedRootSemanticsNode)
            is ViewGroup -> (0 until view.childCount).flatMap { root(view.getChildAt(it)) }
            else -> emptyList()
        }
        return root(host) + android.view.inspector.WindowInspector.getGlobalWindowViews()
            .filter { it !== activity.window.decorView }.flatMap(::root)
    }
    fun texts(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
    fun find(text: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
    fun actionNode(node: SemanticsNode, key: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>>): SemanticsNode {
        var current: SemanticsNode? = node
        while (current != null) {
            if (current.config.getOrNull(key)?.action != null) return current
            current = current.parent
        }
        error("Missing action $key for $node")
    }
    fun click(text: String) {
        val node = find(text) ?: error("Missing '$text'; visible: ${texts()}")
        assertTrue(actionNode(node, SemanticsActions.OnClick).config[SemanticsActions.OnClick].action!!.invoke())
        advance()
    }
    fun scrollTo(text: String) {
        repeat(18) {
            if (find(text) != null) return
            val scroll = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
                ?: error("No scroll container")
            scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 450f)
            advance(18)
        }
        error("Cannot reach '$text'; visible: ${texts()}")
    }
    fun edit(label: String, value: String) {
        val all = nodes()
        val field = all.firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.SetText)?.action != null &&
                (node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(label) } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(label) } == true)
        } ?: all.lastOrNull { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            ?: error("Missing text field $label")
        assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value)))
        advance()
        assertTrue("Edited field must expose the supplied value", nodes().any {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == value
        })
    }
    fun await(message: String, condition: () -> Boolean) {
        repeat(150) { if (condition()) return; frame(); Thread.sleep(5) }
        capture("${style.name.lowercase()}-failed-await")
        assertTrue("$message; visible=${texts()}", condition())
    }
    fun capture(name: String) {
        val output = File(System.getenv("ETA_UI_REVIEW_OUTPUT") ?: "build/g6-ui")
        output.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        host.draw(canvas)
        android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it !== activity.window.decorView }.forEach { window ->
            if (window.width > 0 && window.height > 0) {
                val layer = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
                window.draw(Canvas(layer))
                canvas.drawBitmap(layer, ((width - window.width) / 2).coerceAtLeast(0).toFloat(),
                    (if (style == AppearanceUiStyle.COUI) height - window.height else (height - window.height) / 2).coerceAtLeast(0).toFloat(), null)
                layer.recycle()
            }
        }
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    fun drag(description: String, dy: Float, cancel: Boolean = false) {
        val handle = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }.boundsInRoot.center
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, handle.x, y, 0).also { host.dispatchTouchEvent(it); it.recycle() }
            frame()
        }
        touch(MotionEvent.ACTION_DOWN, handle.y); advance(40)
        touch(MotionEvent.ACTION_MOVE, handle.y + 20f)
        touch(MotionEvent.ACTION_MOVE, handle.y + dy)
        capture("${style.name.lowercase()}-drag-mid${if(cancel) "-cancel" else ""}")
        touch(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, handle.y + dy)
        advance()
    }
    override fun close() {
        host.disposeComposition(); recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy(); renderBuffer.recycle()
    }
}
