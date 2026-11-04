package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import org.junit.Test

class MultimodalSteeringRecoveryTest {
    private val data = "data:image/png;base64,aW1hZ2U="
    @Test fun acceptedButUnconsumedImagesSurviveFailureAndDoNotDispatchAgain() {
        val state = AgentChatUiState(messages = emptyList(), input = "", isStreaming = true, thinkingEnabled = false,
            queuedMessages = listOf(QueuedMessageUi("queue-image", ""), QueuedMessageUi("other", "next")))
        val supplement = AgentUiHandoffPayload.Supplement(1, "", 1, "queue-image", listOf(data))
        val result = AgentRuntimeWire.RunResult("run", false, "", error = "cancelled")
        val first = AgentPendingResultRecovery.apply(state, "run", result, supplements = listOf(supplement))
        assertEquals(listOf("other"), first.state.queuedMessages.map { it.id })
        val visible = first.state.messages.filterIsInstance<UserMessageUi>().single()
        assertEquals(listOf(data), visible.images)
        val history = first.state.history.single { it.role == "user" }
        // Visible attachment remains available; durable model history keeps the existing omission policy.
        assertFalse(history.contentJson.contains(data))
        assertTrue(history.contentJson.contains("图片观察"))
        assertEquals("user-run-supplement-1", history.messageId)
        val again = AgentPendingResultRecovery.apply(first.state, "run", result, supplements = listOf(supplement))
        assertTrue(again.alreadyApplied)
        assertEquals(first.state.history, again.state.history)
        assertEquals(1, again.state.messages.filterIsInstance<UserMessageUi>().size)
    }
    @Test fun repeatedSupplementProjectionKeepsExactlyOneImageMessage() {
        val supplement = AgentUiHandoffPayload.Supplement(1, "检查", 1, "queue-image", listOf(data))
        val initial = AgentPendingResultRecovery.mergeSupplements("run", listOf(supplement), emptyList())
        val repeated = AgentPendingResultRecovery.mergeSupplements("run", listOf(supplement), initial)
        assertEquals(initial, repeated)
        assertEquals(listOf(data), (repeated.single() as UserMessageUi).images)
    }
}
