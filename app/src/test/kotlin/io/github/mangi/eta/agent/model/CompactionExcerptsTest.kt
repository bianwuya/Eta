package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
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
    @Test fun completeUnicodeToolExchangeIsSentOnceWithoutExcerpts() {
        val toolCall = AgentModelClient.ToolCall("fixture-call", "fixture_tool", "{}")
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", "证据😀".repeat(900), toolCallsJson = JSONArray().put(
                JSONObject().put("id", toolCall.id).put("type", "function").put("function",
                    JSONObject().put("name", toolCall.name).put("arguments", toolCall.argumentsJson)),
            ).toString()),
            AgentModelClient.ConversationMessage("tool", "结果😀".repeat(900), toolCallId = toolCall.id),
            AgentModelClient.ConversationMessage("assistant", "完成证据"),
        )
        val bodies = mutableListOf<String>()
        val result = AgentContextSummarizer(config.copy(contextWindow = 128_000), provider { request ->
            bodies += request.messages.getJSONObject(1).getString("content")
            response("完整证据已保留")
        }, AgentRunController(), false).summarize(history)
        val original = "待整理的历史：\n" + history.joinToString("") { AgentConversationCodec.toJsonObject(it).toString() + "\n" }
        assertEquals(listOf(original), bodies)
        assertEquals("完整证据已保留", result)
    }
    @Test fun oversizedUnicodeMessageIsNotExcerptedOrSent() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider { calls++; response("unused") }, AgentRunController(), false)
                .summarize(listOf(AgentModelClient.ConversationMessage("assistant", "证据😀".repeat(900))))
        }
        assertEquals("COMPACTION_MODEL_CAPACITY", failure.code)
        assertEquals(0, calls)
    }
    private fun assertSessionFailurePreservesHistory(oversized: Boolean) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
            .put(JSONObject().put("role", "assistant").put("content", "中文".repeat(if (oversized) 1500 else 100)))
        val original = messages.toString()
        var calls = 0
        var commits = 0
        val session = AgentContextSession(config, messages, 1, "fixture", provider {
            calls++
            throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
        }, AgentRunController(), { emptySet() }, {}, { commits++ })
        val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(if (oversized) "COMPACTION_MODEL_CAPACITY" else "MODEL_TIMEOUT", failure.code)
        assertEquals(if (oversized) 0 else 1, calls)
        assertEquals(original, messages.toString())
        assertEquals(0, commits)
        assertNull(session.snapshot())
    }
    @Test fun capacityFailureDoesNotMutateOriginalHistoryOrCommitSummary() = assertSessionFailurePreservesHistory(true)
    @Test fun requestFailureDoesNotMutateOriginalHistoryOrCommitSummary() = assertSessionFailurePreservesHistory(false)
    @Test fun largeValidSummaryIsNotRejectedByOldCharacterCap() {
        val summary = "必要证据".repeat(4000)
        val result = AgentContextSummarizer(config.copy(contextWindow = 200_000), provider { response(summary) }, AgentRunController(), false)
            .summarize(listOf(AgentModelClient.ConversationMessage("assistant", "source".repeat(5000))))
        assertEquals(summary, result)
        assertTrue(result.length > 12000)
    }
    @Test fun summaryTransportFallsBackOnceWithIdenticalCompleteHistoryAndAccount() {
        val requests = mutableListOf<ProviderRequest>()
        val source = listOf(AgentModelClient.ConversationMessage("assistant", "facts"))
        val result = AgentContextSummarizer(config, provider {
            requests += it
            assertEquals(ProviderRequestPurpose.COMPACTION, it.purpose)
            if (!it.forceHttp1) throw java.io.IOException("stream was reset: CANCEL")
            response("summary")
        }, AgentRunController(), false).summarize(source)
        assertEquals("summary", result)
        assertEquals(listOf(false, true), requests.map { it.forceHttp1 })
        assertEquals(requests[0].messages.toString(), requests[1].messages.toString())
        assertEquals(requests[0].accountBinding, requests[1].accountBinding)
    }
}
