package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionUiTest {
    @Test
    fun manualActionRequiresIdleCompletedHistory() {
        val empty = AgentChatUiState(emptyList(), input = "草稿", isStreaming = false, thinkingEnabled = false)
        assertFalse(empty.canCompactContext)
        val ready = empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "已完成")))
        assertTrue(ready.canCompactContext)
        assertFalse(ready.copy(isStreaming = true).canCompactContext)
        val onlySummary = empty.copy(history = listOf(
            AgentModelClient.ConversationMessage("assistant", "仅摘要", contextSummary = true),
        ))
        assertTrue(onlySummary.canCompactContext)
        assertFalse(onlySummary.copy(isCompacting = true).canCompactContext)
        assertFalse(onlySummary.copy(isStreaming = true).canCompactContext)
    }

    @Test
    fun successfulCompactionInvalidatesOnlyCurrentOccupancyNotSessionTokenTotals() {
        val response = AgentMessageUi("answer", "答案", usage = TokenUsageUi(
            contextTokens = 30_000, inputTokens = 28_000, outputTokens = 2_000,
        ))
        val compaction = SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction,
            "已压缩", compactionSucceeded = true)
        val usage = latestContextUsage(listOf(response, compaction), null)
        assertNull(usage.contextTokens)
        assertEquals(30_000, usage.beforeCompactionTokens)
        assertTrue(usage.sessionStats.hasTokenUsage)
        assertEquals(30_000, usage.sessionStats.totalTokens)
        val next = latestContextUsage(listOf(response, compaction,
            response.copy(id = "next", usage = TokenUsageUi(contextTokens = 4_000))), null)
        assertEquals(4_000, next.contextTokens)
        assertNull(next.beforeCompactionTokens)
    }

    @Test
    fun failedOrTimedOutCompactionKeepsLastActualContextReading() {
        val response = AgentMessageUi("answer", "答案", usage = TokenUsageUi(contextTokens = 30_000))
        val failed = SystemNoticeMessageUi("failed", SystemNoticeCode.ContextCompaction,
            "上下文压缩失败", compactionSucceeded = false)
        val timeout = failed.copy(id = "timeout", detail = "MODEL_TIMEOUT")
        assertEquals(30_000, latestContextUsage(listOf(response, failed), null).contextTokens)
        assertEquals(30_000, latestContextUsage(listOf(response, timeout), null).contextTokens)
        assertEquals(30_000, latestContextUsage(listOf(response, failed, timeout), null).contextTokens)
        assertNull(latestContextUsage(listOf(response, failed, timeout), null).beforeCompactionTokens)
        // A later successful compaction correctly invalidates the older measured value.
        assertNull(latestContextUsage(listOf(response, failed, timeout,
            failed.copy(id = "completed", compactionSucceeded = true)), null).contextTokens)
    }
}
