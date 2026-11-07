package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaAssistantScreenContextOwnershipTest {
    @Test fun submittedCaptureSurvivesEntryReplacementUntilReleased() = runBlocking {
        val old = EtaAssistantScreenContexts.begin(0, listOf(ComponentName("test.pkg", "test.pkg.Main")))
        val submitted = requireNotNull(old.retain())
        val next = EtaAssistantScreenContexts.begin(0, emptyList())
        assertTrue(submitted.snapshot().text.contains("test.pkg"))
        submitted.close()
        assertEquals("", submitted.snapshot().text)
        EtaAssistantScreenContexts.release(next.id)
    }
}
