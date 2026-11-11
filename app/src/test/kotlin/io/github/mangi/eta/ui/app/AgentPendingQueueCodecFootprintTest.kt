package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentPendingQueueCodecFootprintTest {
    @Test fun duplicatedOriginalPreviewIsNotWrittenTwiceAndOldPreviewStillReads() {
        val context = RuntimeEnvironment.getApplication()
        for (bytes in listOf(50 * 1024, 1024 * 1024, 8 * 1024 * 1024)) {
            val original = "data:image/png;base64," + "A".repeat((bytes + 2) / 3 * 4)
            val queue = listOf(QueuedMessageUi("q", "", pendingImages = listOf(PendingImageUi("img", original, original, "image/png"))))
            val started = System.nanoTime()
            val raw = AgentPendingQueueCodec.encode(context, queue)
            val old = JSONObject(raw).also { it.getJSONArray("items").getJSONObject(0).getJSONArray("images")
                .getJSONObject(0).put("preview", original) }.toString()
            println("QUEUE bytes=$bytes oldChars=${old.length} newChars=${raw.length} oldChunks=${(old.length+16383)/16384} newChunks=${(raw.length+16383)/16384} encodeNanos=${System.nanoTime()-started}")
            assertTrue(raw.length < old.length * 0.55)
            assertEquals(original, AgentPendingQueueCodec.decode(raw).single().pendingImages.single().dataUrl)
            assertEquals(original, AgentPendingQueueCodec.decode(old).single().pendingImages.single().dataUrl)
            assertFalse(AgentPendingQueueCodec.decode(raw).single().immediateInterrupt)
        }
    }
}
