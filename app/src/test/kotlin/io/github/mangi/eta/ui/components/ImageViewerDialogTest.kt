package io.github.mangi.eta.ui.components

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.local.richcontent.LocalPreviewFiles
import io.github.mangi.eta.local.richcontent.RichPreviewScreen
import io.github.mangi.eta.local.richcontent.decodePreviewImage
import io.github.mangi.eta.local.richcontent.LocalRichPreview
import io.github.mangi.eta.local.richcontent.LocalAttachmentPreview
import io.github.mangi.eta.local.richcontent.AttachmentPreviewScreen
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.UserMessageUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.nio.charset.Charset
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
    @Test fun pendingTextOpensReadableAndEncodingCanBeChanged() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val fixture = ViewerFixture(activity)
        val text = "中文附件内容，发送前核对。"
        val file = File(activity.filesDir, "中文 #100%附件.txt").apply { writeBytes(text.toByteArray(Charset.forName("GB18030"))) }
        val original = file.readBytes()
        val opened = mutableStateOf<AgentFileReference?>(null)
        try {
            fixture.setContent {
                AgentAppTheme(appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalAttachmentPreview provides { opened.value = it }) {
                        val document = opened.value
                        if (document == null) PendingFileReferenceStrip(
                            listOf(PendingFileReferenceUi("document", AgentFileReference(file.name, file.path, AgentFileReferenceKind.File))), {},
                        ) else AttachmentPreviewScreen(document.absolutePath, document.displayName, onBack = { opened.value = null })
                    }
                }
            }
            var root = fixture.mainRoot()
            fixture.tap(root, requireNotNull(findText(root.semanticsOwner.unmergedRootSemanticsNode, file.name)))
            fixture.awaitMainText(text)
            root = fixture.mainRoot()
            var button = requireNotNull(findText(root.semanticsOwner.rootSemanticsNode, "UTF-8"))
            fixture.tap(root, button)
            fixture.awaitMainText("无法用所选编码读取文件，请切换编码或使用其他应用打开")
            root = fixture.mainRoot()
            button = requireNotNull(findText(root.semanticsOwner.rootSemanticsNode, "GB18030"))
            fixture.tap(root, button)
            fixture.awaitMainText(text)
            org.junit.Assert.assertArrayEquals(original, file.readBytes())
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
            file.delete()
        }
    }

    @Test fun couiPendingDocumentsOpenOnTouch() = assertPendingDocumentsOpen(AppearanceUiStyle.COUI)

    @Test fun miuixPendingDocumentsOpenOnTouch() = assertPendingDocumentsOpen(AppearanceUiStyle.MIUIX)

    private fun assertPendingDocumentsOpen(style: AppearanceUiStyle) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val fixture = ViewerFixture(controller.get())
        val extension = mutableStateOf("txt")
        val opened = mutableListOf<String>()
        val removed = mutableListOf<String>()
        try {
            fixture.setContent {
                AgentAppTheme(appearance = AppearanceSettings(uiStyle = style), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalAttachmentPreview provides { opened += it.absolutePath }) {
                        PendingFileReferenceStrip(
                            references = listOf(PendingFileReferenceUi("document", AgentFileReference(
                                "中文附件.${extension.value}", "/sdcard/Download/中文附件.${extension.value}", AgentFileReferenceKind.File))),
                            onRemoveReference = { removed += it },
                        )
                    }
                }
            }
            for (format in listOf("txt", "md", "pdf", "doc", "docx", "xls", "xlsx")) {
                extension.value = format
                val root = fixture.mainRoot()
                val node = requireNotNull(findText(root.semanticsOwner.unmergedRootSemanticsNode, "中文附件.$format"))
                var clickable: SemanticsNode? = node
                while (clickable != null && !clickable.config.contains(SemanticsActions.OnClick)) clickable = clickable.parent
                assertNotNull("$format attachment must offer preview", clickable)
                assertFalse("$format preview must not be disabled", requireNotNull(clickable).config.contains(SemanticsProperties.Disabled))
                fixture.tap(root, node)
                assertEquals("/sdcard/Download/中文附件.$format", opened.lastOrNull())
                assertTrue(removed.isEmpty())
            }
            val root = fixture.mainRoot()
            val remove = requireNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                controller.get().getString(R.string.ui_remove_file_reference_04bbfc)))
            val count = opened.size
            fixture.tap(root, remove)
            assertEquals(listOf("document"), removed)
            assertEquals("removing an attachment must not open it", count, opened.size)
        } finally {
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun findText(node: SemanticsNode, text: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) return node
        return node.children.firstNotNullOfOrNull { findText(it, text) }
    }

    @Test
    fun couiChatThumbnailOpensViewerOnTouch() =
        assertMessageThumbnailOpensViewer(AppearanceUiStyle.COUI, overlay = false)

    @Test
    fun miuixChatThumbnailOpensViewerOnTouch() =
        assertMessageThumbnailOpensViewer(AppearanceUiStyle.MIUIX, overlay = false)

    @Test
    fun couiAssistantThumbnailOpensViewerOnTouch() =
        assertMessageThumbnailOpensViewer(AppearanceUiStyle.COUI, overlay = true)

    @Test
    fun miuixAssistantThumbnailOpensViewerOnTouch() =
        assertMessageThumbnailOpensViewer(AppearanceUiStyle.MIUIX, overlay = true)

    private fun assertMessageThumbnailOpensViewer(style: AppearanceUiStyle, overlay: Boolean) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val fixture = ViewerFixture(activity)
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            val imageUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            fixture.setContent {
                AgentAppTheme(appearance = AppearanceSettings(uiStyle = style), applyInterfaceScale = false) {
                    ChatImagePreviewHost {
                    ChatMessageItem(
                        message = UserMessageUi(id = "image-entry", content = "", images = listOf(imageUrl)),
                        onSuggestionClick = {},
                        onRunTraceClick = {},
                        onOpenBrowser = {},
                        showBrowserShortcut = false,
                        assistantOverlay = overlay,
                    )
                    }
                }
            }
            fixture.advance(12)
            val root = fixture.mainRoot()
            val thumbnail = requireNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_view_image)))
            assertTrue(fixture.showingDialogs().isEmpty())
            fixture.tap(root, thumbnail)
            fixture.advance(60)
            val images = allDescriptions(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment))
            assertEquals("one full-screen canvas must exist", 1, images.size)
            // Force the covering Canvas through measure/layout before hit testing its window bounds.
            root.measureAndLayoutForTest()
            fixture.tap(root, requireNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment))))
            fixture.advance(ViewConfiguration.getDoubleTapTimeout() / 16 + 80)
            assertEquals(0, allDescriptions(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_image_attachment)).size)
            assertNotNull(findDescription(root.semanticsOwner.unmergedRootSemanticsNode,
                activity.getString(R.string.chat_view_image)))
        } finally {
            bitmap.recycle()
            try { fixture.close() } finally { controller.pause().stop().destroy() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

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
            // Report a decode failure directly instead of mistaking it for a missing Dialog.
            val reference = Uri.encode(file.absolutePath, "/\\")
            val decoded = decodePreviewImage(activity, reference)
            assertEquals(64, decoded.width)
            assertEquals(32, decoded.height)
            val routeOpen = mutableStateOf(true)
            var backs = 0
            var routeDisposals = 0
            fixture.setContent {
                MiuixTheme {
                    if (routeOpen.value) {
                        DisposableEffect(Unit) { onDispose { routeDisposals++ } }
                        RichPreviewScreen(reference = reference, onBack = {
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

        fun mainRoot(): ViewRootForTest {
            advance(4)
            return requireNotNull(findRoot(host)).also { it.measureAndLayoutForTest() }
        }

        fun awaitMainText(text: String) {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            fun contains(node: SemanticsNode): Boolean =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || node.children.any(::contains)
            do {
                val root = mainRoot()
                if (contains(root.semanticsOwner.rootSemanticsNode)) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            error("Expected preview text was not displayed: $text")
        }

        fun tap(root: ViewRootForTest, node: SemanticsNode) {
            val bounds = node.boundsInRoot
            assertTrue("empty image bounds: $bounds", bounds.width > 0 && bounds.height > 0)
            val location = IntArray(2).also { root.view.getLocationOnScreen(it) }
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x + location[0], bounds.center.y + location[1], 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                }
                try {
                    assertTrue(root.view.dispatchTouchEvent(event))
                } finally { event.recycle() }
                advance()
            }
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

    private fun allDescriptions(node: SemanticsNode, description: String): List<SemanticsNode> =
        (if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) listOf(node) else emptyList()) +
            node.children.flatMap { allDescriptions(it, description) }

    private fun findDescription(node: SemanticsNode, description: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) return node
        return node.children.firstNotNullOfOrNull { findDescription(it, description) }
    }
}
