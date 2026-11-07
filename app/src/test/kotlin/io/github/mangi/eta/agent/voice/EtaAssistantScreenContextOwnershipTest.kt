package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaAssistantScreenContextOwnershipTest {
    @Test fun replacedCaptureCannotAcceptLateScreenshotsIntoAnotherInvocation() = runBlocking {
        val old = EtaAssistantScreenContexts.begin(VoiceInteractionSession.SHOW_WITH_SCREENSHOT, emptyList())
        val submitted = requireNotNull(old.retain())
        assertTrue(old.hasPendingSystemCallbacks())
        old.sealCallbacks()
        val next = EtaAssistantScreenContexts.begin(VoiceInteractionSession.SHOW_WITH_SCREENSHOT, emptyList())
        // The framework has no invocation token: the Session seals a new capture when old callbacks remain.
        next.sealCallbacks(ambiguous = true)
        val lateOld = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val lateNew = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        old.acceptScreenshot(lateOld)
        next.acceptScreenshot(lateNew)
        assertTrue(lateOld.isRecycled)
        assertTrue(lateNew.isRecycled)
        assertNull(submitted.snapshot().image)
        assertNull(next.snapshot().image)
        assertTrue(next.snapshot().text.contains("无法确认"))
        submitted.close()
        EtaAssistantScreenContexts.release(next.id)
    }

    @Test fun acceptedScreenshotFinishesAfterEntryReleaseForSubmittedOwner() = runBlocking {
        val capture = EtaAssistantScreenContexts.begin(VoiceInteractionSession.SHOW_WITH_SCREENSHOT, emptyList())
        val submitted = requireNotNull(capture.retain())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        capture.acceptScreenshot(bitmap)
        capture.sealCallbacks()
        EtaAssistantScreenContexts.release(capture.id)
        val snapshot = submitted.snapshot()
        assertNotNull(snapshot.image)
        submitted.close()
        assertNull(capture.retain())
    }

    @Test fun submittedCaptureSurvivesEntryReplacementUntilReleased() = runBlocking {
        val old = EtaAssistantScreenContexts.begin(0, listOf(ComponentName("test.pkg", "test.pkg.Main")))
        val submitted = requireNotNull(old.retain())
        val next = EtaAssistantScreenContexts.begin(0, emptyList())
        assertTrue(submitted.snapshot().text.contains("test.pkg"))
        submitted.close()
        assertEquals("", submitted.snapshot().text)
        EtaAssistantScreenContexts.release(next.id)
    }

    @Test fun retiredEndpointCannotContaminateFreshSessionCapture() = runBlocking {
        val retired = EtaAssistantScreenContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT, emptyList())
        retired.sealCallbacks(ambiguous = true)
        retired.close()
        val fresh = EtaAssistantScreenContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT, emptyList())
        try {
            val late = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            retired.acceptScreenshot(late)
            fresh.acceptScreenshot(Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888))
            assertTrue(late.isRecycled)
            assertNotNull(fresh.snapshot().image)
            assertFalse(fresh.snapshot().text.contains("无法确认"))
        } finally { fresh.close() }
    }
}
