package io.github.mangi.eta.ui.components

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.local.richcontent.LocalPreviewFiles
import io.github.mangi.eta.local.richcontent.RichPreviewScreen
import java.io.ByteArrayOutputStream
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageViewerDialogTest {
    @Test
    fun bitmapEntryUsesExistingViewerAndSingleTapDismissesIt() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = ViewerFixture(controller.get())
        try {
            val activity = controller.get()
            val show = mutableStateOf(true)
            val bitmap = ImageBitmap(100, 100)
            var dismissals = 0
            fixture.setContent {
                if (show.value) ImageViewerDialog(bitmap = bitmap, onDismiss = {
                    dismissals++
                    show.value = false
                })
            }
            val dialog = fixture.awaitDialog()
            val root = fixture.dialogRoot(dialog)
            val image = findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment))
            val close = findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(android.R.string.cancel))
            assertNotNull(image)
            assertNotNull(close)

            val bounds = requireNotNull(image).boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertTrue("dialog Compose root must be attached", root.view.isAttachedToWindow)
            val location = IntArray(2).also { root.view.getLocationOnScreen(it) }
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                // MotionEvent's raw coordinates must include the dialog window offset:
                // Compose maps getRawX/Y back to root-local coordinates for hit testing.
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x + location[0], bounds.center.y + location[1], 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                }
                try {
                    assertTrue("dialog Compose root must accept touch $action", root.view.dispatchTouchEvent(event))
                } finally { event.recycle() }
                fixture.advance()
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            // Single tap is delayed until the double-tap timeout on Android's main looper.
            fixture.advance(ViewConfiguration.getDoubleTapTimeout() / 16 + 3)
            assertEquals(1, dismissals)
            assertFalse(dialog.isShowing)
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun existingDataUrlEntryStillOpensTheViewerAndDismisses() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = ViewerFixture(controller.get())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            val imageUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            assertNotNull("the test data URL must decode", decodeDataUrlBitmap(imageUrl))
            val currentUrl = mutableStateOf<String?>(null)
            var dismissals = 0
            fixture.setContent {
                ImageViewerDialog(dataUrl = currentUrl.value, onDismiss = {
                    dismissals++
                    currentUrl.value = null
                })
            }
            fixture.advance(4)
            assertTrue("null URL must not open a dialog", fixture.showingDialogs().isEmpty())
            currentUrl.value = imageUrl
            val dialog = fixture.awaitDialog()
            val root = fixture.dialogRoot(dialog)
            assertNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                controller.get().getString(R.string.chat_image_attachment)))
            val close = requireNotNull(findDescription(root.semanticsOwner.rootSemanticsNode,
                controller.get().getString(android.R.string.cancel)))
            assertTrue(close.config[SemanticsActions.OnClick].action?.invoke() == true)
            fixture.advance(4)
            assertEquals(1, dismissals)
            assertFalse(dialog.isShowing)
        } finally {
            bitmap.recycle()
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun localPreviewDismissalRequestsBackAndRemovesItsHost() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val fixture = ViewerFixture(activity)
        val file = File.createTempFile("preview-route-test-", ".png", LocalPreviewFiles.directory(activity))
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val routeOpen = mutableStateOf(true)
            var backs = 0
            var routeDisposals = 0
            fixture.setContent {
                MiuixTheme {
                    if (routeOpen.value) {
                        DisposableEffect(Unit) { onDispose { routeDisposals++ } }
                        RichPreviewScreen(reference = file.path, onBack = {
                            backs++
                            routeOpen.value = false
                        })
                    }
                }
            }
            // RichPreviewScreen decodes on Dispatchers.IO; wait with a real-time bound while
            // driving both main-looper messages and the Compose recomposition clock.
            val dialog = fixture.awaitDialog(waitForIo = true)
            val root = fixture.dialogRoot(dialog)
            assertNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment)))
            val close = requireNotNull(findDescription(root.semanticsOwner.rootSemanticsNode,
                activity.getString(android.R.string.cancel)))
            assertTrue(close.config[SemanticsActions.OnClick].action?.invoke() == true)
            fixture.advance(4)
            assertEquals(1, backs)
            assertFalse(routeOpen.value)
            assertEquals("preview route must leave composition", 1, routeDisposals)
            assertFalse(dialog.isShowing)
        } finally {
            bitmap.recycle()
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
            file.delete()
        }
    }

    // Same explicit frame-clock pattern used by EtaVoicePanelTouchTest: a paused
    // Robolectric looper is not a Compose frame clock. Each test owns its recomposer,
    // host and dialogs, so neither a prior window nor a pending frame can be reused.
    private class ViewerFixture(private val activity: ComponentActivity) {
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)
        private val priorDialogs = ShadowDialog.getShownDialogs().toSet()

        fun setContent(content: @Composable () -> Unit) {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent(content)
            activity.setContentView(host)
        }

        fun showingDialogs(): List<Dialog> = ShadowDialog.getShownDialogs()
            .filter { it !in priorDialogs && it.isShowing && belongsToActivity(it.context) }
            .distinct()

        private fun belongsToActivity(context: Context): Boolean {
            var current = context
            while (true) {
                if (current === activity) return true
                if (current !is ContextWrapper) return false
                val base = current.baseContext
                if (base === current) return false
                current = base
            }
        }

        fun awaitDialog(waitForIo: Boolean = false): Dialog {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var frames = 0
            do {
                advance()
                frames++
                val dialogs = showingDialogs()
                check(dialogs.size <= 1) { "expected one new dialog, got ${dialogs.size}" }
                if (dialogs.size == 1) return dialogs.single()
                if (waitForIo) Thread.sleep(5)
            } while (System.nanoTime() < deadline && (waitForIo || frames < 120))
            error("viewer dialog was not shown after $frames frames")
        }

        fun advance(frames: Int = 1) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                layout(activity.findViewById(android.R.id.content))
                showingDialogs().forEach { layout(requireNotNull(it.window).decorView) }
            }
        }

        fun dialogRoot(dialog: Dialog): ViewRootForTest {
            advance(2)
            val decor = requireNotNull(dialog.window).decorView
            layout(decor)
            return requireNotNull(findRoot(decor)).also { it.measureAndLayoutForTest() }
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

        private fun layout(view: View) {
            view.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1792, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 822, 1792)
        }

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }

    private fun findDescription(node: SemanticsNode, description: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) return node
        return node.children.firstNotNullOfOrNull { findDescription(it, description) }
    }
}
