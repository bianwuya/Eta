package io.github.mangi.eta.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
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
        try {
            val activity = controller.get()
            val show = mutableStateOf(true)
            val bitmap = ImageBitmap(100, 100)
            var dismissals = 0
            activity.setContent {
                if (show.value) ImageViewerDialog(bitmap = bitmap, onDismiss = {
                    dismissals++
                    show.value = false
                })
            }
            repeat(4) { advance(); layoutContent(activity) }
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            val root = dialogRoot(dialog)
            val image = findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment))
            val close = findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(android.R.string.cancel))
            assertNotNull(image)
            assertNotNull(close)

            val bounds = requireNotNull(image).boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0)
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x, bounds.center.y, 0)
                try { root.view.dispatchTouchEvent(event) } finally { event.recycle() }
                advance(16)
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            // detectTapGestures waits for the double-tap window before accepting a single tap.
            advance(400)
            repeat(4) { advance() }
            assertEquals(1, dismissals)
            assertFalse(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun existingDataUrlEntryStillOpensTheViewerAndDismisses() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            val imageUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            val currentUrl = mutableStateOf<String?>(null)
            var dismissals = 0
            controller.get().setContent {
                ImageViewerDialog(dataUrl = currentUrl.value, onDismiss = {
                    dismissals++
                    currentUrl.value = null
                })
            }
            repeat(4) { advance(); layoutContent(controller.get()) }
            val previousDialog = ShadowDialog.getLatestDialog()
            currentUrl.value = imageUrl
            repeat(4) { advance(); layoutContent(controller.get()) }
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog !== previousDialog && dialog.isShowing)
            val root = dialogRoot(dialog)
            val close = requireNotNull(findDescription(root.semanticsOwner.rootSemanticsNode,
                controller.get().getString(android.R.string.cancel)))
            assertTrue(close.config[SemanticsActions.OnClick].action?.invoke() == true)
            repeat(4) { advance() }
            assertEquals(1, dismissals)
            assertFalse(dialog.isShowing)
        } finally {
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun localPreviewDismissalRequestsBackAndRemovesItsHost() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val file = File(LocalPreviewFiles.directory(activity), "preview-route-test.png")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val routeOpen = mutableStateOf(true)
            var backs = 0
            val previousDialog = ShadowDialog.getLatestDialog()
            activity.setContent {
                MiuixTheme {
                    if (routeOpen.value) RichPreviewScreen(reference = file.path, onBack = {
                        backs++
                        routeOpen.value = false
                    })
                }
            }
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var dialog = ShadowDialog.getLatestDialog()
            while ((dialog == null || dialog === previousDialog || !dialog.isShowing) && System.nanoTime() < deadline) {
                advance(20)
                layoutContent(activity)
                Thread.sleep(5) // decodePreviewImage runs on Dispatchers.IO.
                dialog = ShadowDialog.getLatestDialog()
            }
            val previewDialog = requireNotNull(dialog)
            assertTrue(previewDialog !== previousDialog && previewDialog.isShowing)
            val root = dialogRoot(previewDialog)
            val close = requireNotNull(findDescription(root.semanticsOwner.rootSemanticsNode,
                activity.getString(android.R.string.cancel)))
            assertTrue(close.config[SemanticsActions.OnClick].action?.invoke() == true)
            repeat(4) { advance() }
            assertEquals(1, backs)
            assertFalse(routeOpen.value)
            assertFalse(previewDialog.isShowing)
        } finally {
            bitmap.recycle()
            file.delete()
            controller.pause().stop().destroy()
        }
    }

    private fun advance(millis: Long = 100) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun layoutContent(activity: ComponentActivity) {
        layout(activity.findViewById<ViewGroup>(android.R.id.content))
    }

    private fun dialogRoot(dialog: android.app.Dialog): ViewRootForTest {
        val decor = requireNotNull(dialog.window).decorView
        layout(decor)
        return requireNotNull(findRoot(decor)).also { it.measureAndLayoutForTest() }
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

    private fun findDescription(node: SemanticsNode, description: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) return node
        return node.children.firstNotNullOfOrNull { findDescription(it, description) }
    }
}
