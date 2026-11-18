package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentReceiptOnlyRecoveryTest {
    private val original = AgentModelClient.ConversationMessage("user", "原消息", messageId = "user-run-1")
    private val received = UserMessageUi("user-run-1-supplement-1", "已接收引导")
    private val additional = UserMessageUi("user-run-1-supplement-2", "同批引导")

    @Test
    fun receiptOnlyCheckpointCommitsVisibleUserInputsAndIgnoresUnrelatedMessage() {
        val messages = listOf(
            UserMessageUi(original.messageId, original.content), received, additional,
            UserMessageUi("user-run-other-supplement-1", "其他会话回执"),
            SystemNoticeMessageUi("interrupted-run-1", SystemNoticeCode.Interrupted),
        )
        val recovered = AgentRuntimeHistoryReducer.retainReceiptsOnInterrupt(
            state(messages), "run-1", setOf(received.id, additional.id),
        )
        assertEquals(listOf(original.messageId, received.id, additional.id), recovered.history.map { it.messageId })
        assertEquals(listOf("原消息", "已接收引导", "同批引导"), recovered.history.map { it.content })
        assertEquals(recovered.history, recovered.journal)
        assertEquals(messages, recovered.messages)
        assertEquals(listOf("run-1"), recovered.appliedRuntimeRunIds)
    }

    @Test
    fun anotherColdStartAndAlreadyStoredIdsDoNotAppendDuplicateHistory() {
        val persistedReceipt = AgentModelClient.ConversationMessage("user", received.content, messageId = received.id)
        val partial = state(listOf(received, additional)).copy(
            history = listOf(original, persistedReceipt),
            journal = listOf(original),
        )
        val sameReceiptIds = listOf(received.id, received.id, additional.id).toSet()
        val first = AgentRuntimeHistoryReducer.retainReceiptsOnInterrupt(partial, "run-1", sameReceiptIds)
        assertEquals(listOf(original.messageId, received.id, additional.id), first.history.map { it.messageId })
        assertEquals(first.history, first.journal)
        val fromDisk = first.copy(history = first.history.toList(), journal = first.journal.toList())
        assertSame(fromDisk, AgentRuntimeHistoryReducer.retainReceiptsOnInterrupt(fromDisk, "run-1", sameReceiptIds))
    }

    @Test
    fun noMatchingReceiptDoesNotMarkTheRuntimeResultApplied() {
        val current = state(listOf(received))
        assertSame(current, AgentRuntimeHistoryReducer.retainReceiptsOnInterrupt(current, "run-1", emptySet()))
        assertSame(current, AgentRuntimeHistoryReducer.retainReceiptsOnInterrupt(current, "run-1", setOf("other")))
    }

    private fun state(messages: List<io.github.mangi.eta.ui.model.AgentChatMessageUi>) = AgentChatUiState(
        messages = messages, history = listOf(original), input = "", isStreaming = false, thinkingEnabled = false,
    )
}
