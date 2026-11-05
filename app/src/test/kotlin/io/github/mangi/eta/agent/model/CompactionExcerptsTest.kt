package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactionExcerptsTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "summary", systemPrompt = "", contextWindow = 6000)
    private fun response(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun provider(action: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = action(request)
    }
    @Test fun oversizedUnicodeMessageIsExcerptedWithoutLosingAnySource() {
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "证据😀".repeat(900)))
        val fragments = mutableListOf<String>()
        var mergeSeen = false
        val result = AgentContextSummarizer(config, provider { request ->
            assertTrue(request.messages.toString().toByteArray().size <= 6000 - 1500 - 1024)
            val body = request.messages.getJSONObject(1).getString("content")
            if (body.startsWith("完整历史批次")) fragments += body.substringAfter('\n') else mergeSeen = true
            response("片段证据已保留")
        }, AgentRunController(), false).summarize(history)
        val original = "待整理的历史：\n" + AgentConversationCodec.toJsonObject(history.single()).toString() + "\n"
        assertEquals(original, fragments.joinToString(""))
        assertTrue(fragments.size > 1)
        fragments.zipWithNext().forEach { (a, b) ->
            assertFalse(a.last().isHighSurrogate())
            assertFalse(b.first().isLowSurrogate())
        }
        assertTrue(mergeSeen)
        assertEquals("片段证据已保留", result)
    }
    @Test fun ordinaryMultiMessageChunksKeepWholeMessages() {
        var excerpts = 0
        AgentContextSummarizer(config, provider {
            if (it.messages.getJSONObject(1).getString("content").startsWith("完整历史批次")) excerpts++
            response("summary")
        }, AgentRunController(), false).summarize((1..3).map { AgentModelClient.ConversationMessage("assistant", "evidence-$it "+"x".repeat(2200)) })
        assertEquals(0, excerpts)
    }
    @Test fun failedLaterExcerptDoesNotMutateOriginalHistoryOrCommitSummary() {
        val messages = org.json.JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
            .put(JSONObject().put("role", "assistant").put("content", "中文".repeat(1500)))
        val original = messages.toString()
        var calls = 0
        var commits = 0
        val session = AgentContextSession(config, messages, 1, "fixture", provider {
            if (++calls == 2) throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
            response("summary")
        }, AgentRunController(), { emptySet() }, {}, { commits++ })
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(2, calls)
        assertEquals(original, messages.toString())
        assertEquals(0, commits)
        assertNull(session.snapshot())
    }
    @Test fun largeValidSummaryIsNotRejectedByOldCharacterCap() {
        val summary = "必要证据".repeat(4000)
        val result = AgentContextSummarizer(config.copy(contextWindow = 200_000), provider { response(summary) }, AgentRunController(), false)
            .summarize(listOf(AgentModelClient.ConversationMessage("assistant", "source".repeat(5000))))
        assertEquals(summary, result)
        assertTrue(result.length > 12000)
    }
    @Test fun summaryTransportFallsBackOnceWithoutChangingAccountOrPurpose() {
        val flags = mutableListOf<Boolean>()
        val source = listOf(AgentModelClient.ConversationMessage("assistant", "facts"))
        val result = AgentContextSummarizer(config, provider {
            flags += it.forceHttp1
            assertEquals(ProviderRequestPurpose.COMPACTION, it.purpose)
            if (!it.forceHttp1) throw java.io.IOException("stream was reset: CANCEL")
            response("summary")
        }, AgentRunController(), false).summarize(source)
        assertEquals("summary", result)
        assertEquals(listOf(false, true), flags)
    }
}
