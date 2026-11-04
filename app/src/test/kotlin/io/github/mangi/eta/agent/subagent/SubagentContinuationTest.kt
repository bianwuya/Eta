package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.junit.Assert.*
import org.junit.Test

class SubagentContinuationTest {
    @Test fun noSnapshotKeepsOriginalPromptAndTranscript() {
        val prior = listOf(ConversationMessage("assistant", "prior"))
        val transcript = listOf(ConversationMessage("assistant", "result"))
        val history = SubagentContinuation.history(prior, "new goal", transcript, null)
        assertEquals("prior", history.first().content)
        assertEquals("new goal", history[1].content)
        assertEquals(transcript.last(), history.last())
    }
    @Test fun snapshotReplacesPriorPromptAndOnlyCoveredTranscript() {
        val prior = listOf(ConversationMessage("assistant", "large-old-history"))
        val transcript = listOf(ConversationMessage("assistant", "covered"),
            ConversationMessage("user", "new correction"), ConversationMessage("assistant", "tail"))
        val snapshot = AgentContextSnapshot(operationId = "child", messages = listOf(
            ConversationMessage("assistant", "effective summary", contextSummary = true),
            ConversationMessage("user", "goal preserved")), consumedTranscriptMessages = 1)
        val resumed = SubagentContinuation.history(prior, "goal", transcript, snapshot)
        assertEquals(listOf("effective summary", "goal preserved", "new correction", "tail"), resumed.map { it.content })
    }
    @Test fun invalidCoverageIsNotSilentlyReplacedWithOldLongHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            SubagentContinuation.history(emptyList(), "goal", emptyList(),
                AgentContextSnapshot(operationId = "child", messages = emptyList(), consumedTranscriptMessages = 2))
        }
    }
}
