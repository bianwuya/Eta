package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSupplementProjectionTest {
    @Test fun imageOnlySupplementUsesSameStableIdAndKeepsImagesOnReplay() {
        val service = Robolectric.buildService(EtaAssistantOverlayService::class.java).get()
        val project = EtaAssistantOverlayService::class.java.getDeclaredMethod("projectRuntimeEvent",
            String::class.java, AgentEvent::class.java, EtaVoiceUiState::class.java).apply { isAccessible = true }
        val event = AgentEvent.UserSupplementReceived(1, "", "q", listOf("data:image/png;base64,fixture"))
        val first = project.invoke(service, "voice", event, EtaVoiceUiState()) as EtaVoiceUiState
        val replay = project.invoke(service, "voice", event, first) as EtaVoiceUiState
        val user = replay.messages.filterIsInstance<UserMessageUi>().single()
        assertEquals("user-voice-supplement-1", user.id)
        assertEquals(event.images, user.images)
        assertEquals("", user.content)
    }
}
