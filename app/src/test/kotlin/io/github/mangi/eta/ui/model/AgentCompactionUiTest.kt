package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompactionUiTest {
    private val model = AgentModelOptionUi(
        id = "model",
        providerId = "provider",
        providerName = "Provider",
        providerSourceType = "custom",
        modelId = "model",
        displayName = "Model",
        contextWindow = 100_000,
    )

    @Test
    fun manualActionRequiresIdleCompletedHistory() {
        val empty = AgentChatUiState(emptyList(), input = "草稿", isStreaming = false, thinkingEnabled = false)
        assertFalse(empty.canCompactContext)
        val ready = empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "已完成")))
        assertTrue(ready.canCompactContext)
        assertFalse(ready.copy(isStreaming = true).canCompactContext)
        assertFalse(empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "仅摘要", contextSummary = true))).canCompactContext)
    }

    @Test
    fun explicitMeasurementIsAvailableWhenCompactionStartsAndAfterItCompletes() {
        val compaction = compactionNotice().copy(running = true, tokensBefore = 32_000)
        val started = latestContextUsage(listOf(compaction), model)
        val completed = latestContextUsage(listOf(compaction.copy(running = false)), null)

        for (usage in listOf(started, completed)) {
            assertEquals(32_000, usage.beforeCompactionTokens)
            assertTrue(usage.hasMeasurement)
            assertNull(usage.contextTokens)
            assertNull(usage.progress)
        }
    }

    @Test
    fun absentExplicitMeasurementFallsBackToLatestReadingAfterEarlierCompaction() {
        val messages = listOf(
            replyWithMeasurement("old", 80_000),
            compactionNotice("earlier"),
            replyWithMeasurement("fresh", 30_000),
            AgentMessageUi("unmeasured", "答案", usage = TokenUsageUi(outputTokens = 100)),
            compactionNotice(),
        )
        val usage = latestContextUsage(messages, model)

        assertTrue(usage.hasMeasurement)
        assertEquals(30_000, usage.beforeCompactionTokens)
        assertNull(usage.contextTokens)
        assertNull(usage.progress)
    }

    @Test
    fun unmeasuredCompactionDoesNotReuseEarlierCompactionOrSummaryUsage() {
        val compaction = compactionNotice().copy(
            contextTokens = 3_000, // 旧版 tokensAfter
            usage = TokenUsageUi(contextTokens = 20_000, inputTokens = 19_000),
        )
        val earlier = listOf(
            replyWithMeasurement("old", 80_000),
            compactionNotice("earlier").copy(tokensBefore = 80_000),
        )
        for (messages in listOf(emptyList(), listOf(compaction), earlier + compaction)) {
            val usage = latestContextUsage(messages, model)
            assertFalse(usage.hasMeasurement)
            assertNull(usage.beforeCompactionTokens)
            assertNull(usage.contextTokens)
            assertNull(usage.progress)
        }
    }

    @Test
    fun newModelMeasurementReplacesHistoricalUsage() {
        val history = listOf(
            replyWithMeasurement("old", 30_000),
            compactionNotice().copy(tokensBefore = 32_000),
            AgentMessageUi("unmeasured", "答案", usage = TokenUsageUi(inputTokens = 3_000)),
        )
        val pending = latestContextUsage(history, model)
        assertEquals(32_000, pending.beforeCompactionTokens)
        assertNull(pending.progress)

        val next = latestContextUsage(history + replyWithMeasurement("next", 4_000), model)
        assertTrue(next.hasMeasurement)
        assertNull(next.beforeCompactionTokens)
        assertEquals(4_000, next.contextTokens)
        assertEquals(0.04f, next.progress ?: -1f, 0.0001f)
    }

    private fun compactionNotice(id: String = "compact") =
        SystemNoticeMessageUi(id, SystemNoticeCode.ContextCompaction, "已压缩")

    private fun replyWithMeasurement(id: String, tokens: Int) =
        AgentMessageUi(id, "答案", usage = TokenUsageUi(contextTokens = tokens))
}
