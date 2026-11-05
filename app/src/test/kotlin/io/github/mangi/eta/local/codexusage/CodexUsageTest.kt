package io.github.mangi.eta.local.codexusage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexUsageTest {
    @Test fun usageIsActualRemainingWithIndependentDynamicWindows() {
        val snapshot = CodexUsageRepository.parse(JSONObject("""{
            "rate_limit":{"primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_at":123}},
            "additional_rate_limits":[{"metered_feature":"image_gen","limit_name":"图片",
                "rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":900,"reset_at":456}}}]
        }"""), 1000)
        assertEquals(2, snapshot.windows.size)
        assertEquals(58.0, snapshot.windows[0].remaining!!, 0.001)
        assertEquals(0.0, snapshot.windows[1].remaining!!, 0.001)
        assertEquals(123L, snapshot.windows[0].resetAt)
        assertTrue(windowName(snapshot.windows[0]).contains("5小时"))
        assertTrue(windowName(snapshot.windows[1]).contains("15分钟"))
        assertEquals(1000L, snapshot.updatedAt)
    }
    @Test fun missingPercentNeverMeansZeroOrFull() {
        val snapshot = CodexUsageRepository.parse(JSONObject("""{"rate_limit":{"allowed":false,"primary_window":{"reset_at":123}}}"""), 1)
        assertNull(snapshot.windows.single().remaining)
        assertNull(snapshot.windows.single().seconds)
        assertTrue(CodexUsageRepository.parse(JSONObject("{}"), 1).windows.isEmpty())
    }
    @Test fun invalidPercentageRemainsUnknownInsteadOfPlausibleQuota() {
        val snapshot = CodexUsageRepository.parse(JSONObject("""{"rate_limit":{"primary_window":{"used_percent":500},"secondary_window":{"used_percent":null}}}"""), 1)
        assertTrue(snapshot.windows.all { it.remaining == null })
    }
}
