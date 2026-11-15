package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class SessionStatsMergeTest {
    @Test fun everyPageBoundaryPreservesCacheAttributionAndCompactionPayback() {
        val messages = listOf(
            AgentMessageUi("a", "", usage = TokenUsageUi(inputTokens = 40_000, cachedTokens = 35_000)),
            SystemNoticeMessageUi("fold", SystemNoticeCode.ContextCompaction, archiveId = "fold-1",
                tokensBefore = 40_000, usage = TokenUsageUi(inputTokens = 40_000, cachedTokens = 0,
                    cacheWriteTokens = 38_000, outputTokens = 3_000)),
            AgentMessageUi("b", "", usage = TokenUsageUi(inputTokens = 12_000, cachedTokens = 2_000)),
            AgentMessageUi("c", "", usage = TokenUsageUi(inputTokens = 13_000, cachedTokens = 0)),
        )
        val expected = latestContextUsage(messages, null).sessionStats
        for (split in 0..messages.size) {
            val prefix = deriveSessionStatsSummary(messages.take(split))
            assertEquals("split=$split", expected,
                latestContextUsage(messages.drop(split), null, prefix).sessionStats)
        }
    }

    @Test fun pagedPrefixDoesNotDropOfficialCacheCompactionOrChildUsage() {
        val old = SessionStatsSummaryUi(cacheWriteTokens = 9, hasCacheWriteTokens = true,
            compactionCount = 1, compactionPromptTokens = 30, subagentCount = 1,
            subagentPromptTokens = 50, ttlOrPrefixMissTokens = 5, lastCompactionArchiveId = "fold-1")
        val result = old + SessionStatsSummaryUi(promptTokens = 10)
        assertEquals(9, result.cacheWriteTokens)
        assertTrue(result.hasCacheWriteTokens)
        assertEquals(1, result.compactionCount)
        assertEquals(30, result.compactionPromptTokens)
        assertEquals(50, result.subagentPromptTokens)
        assertEquals(5, result.ttlOrPrefixMissTokens)
        assertEquals("fold-1", result.lastCompactionArchiveId)
        assertEquals(90, result.totalTokens)
    }
}
