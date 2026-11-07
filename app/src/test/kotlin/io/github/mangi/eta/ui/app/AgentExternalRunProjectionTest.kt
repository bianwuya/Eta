package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentExternalRunProjectionTest {
    private val empty = AgentChatHomeUiState(messages = emptyList(), input = "unsent draft",
        isStreaming = false, thinkingEnabled = false)

    @Test fun preparingIsNotFabricatedAsAUserMessageOrModelHistory() {
        assertSame(empty, AgentExternalRunProjection.withInput(empty, "power", "", emptyList()))
    }

    @Test fun deferredInputAndImagesRefreshOneRunWithoutLosingDraftOrTrace() {
        val preview = "data:image/png;base64,aW1hZ2U="
        val first = AgentExternalRunProjection.withInput(empty, "power", "actual prompt", listOf(preview))
        val streaming = first.copy(messages = first.messages + AgentMessageUi("assistant-power-1-0", "live", true))
        val second = AgentExternalRunProjection.withInput(streaming, "power", "actual prompt", listOf(preview))
        assertEquals("unsent draft", second.input)
        assertEquals(1, second.messages.filterIsInstance<UserMessageUi>().size)
        assertEquals(listOf(preview), second.messages.filterIsInstance<UserMessageUi>().single().images)
        assertEquals("live", second.messages.filterIsInstance<AgentMessageUi>().single().content)
        assertEquals(1, second.history.count { it.role == "user" })
        assertEquals(1, second.journal.count { it.role == "user" })
    }

    @Test fun nextSameKeyRunGetsOneNewInputAndKeepsPreviousTurn() {
        val first = AgentExternalRunProjection.withInput(empty, "first", "first prompt", emptyList())
        val next = AgentExternalRunProjection.withInput(first, "second", "second prompt", emptyList())
        val replay = AgentExternalRunProjection.withInput(next, "second", "second prompt", emptyList())
        assertEquals(listOf("user-first", "user-second"), replay.messages.map { it.id })
        assertEquals(2, replay.history.count { it.role == "user" })
    }

    @Test fun lateInputIsPlacedBeforeItsAlreadyReplayedTrace() {
        val replayed = empty.copy(messages = listOf(AgentMessageUi("assistant-power-1-0", "live", true)))
        val ready = AgentExternalRunProjection.withInput(replayed, "power", "real input", emptyList())
        assertEquals(listOf("user-power", "assistant-power-1-0"), ready.messages.map { it.id })
    }
}
