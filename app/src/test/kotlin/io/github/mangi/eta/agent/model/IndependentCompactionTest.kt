package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IndependentCompactionTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "task", systemPrompt = "task rules", contextWindow = 128_000)
    private fun provider(run: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit) = run(request)
    }
    private fun response(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    @Test fun manualCompactionUsesIndependentModelAndCustomPrompt() {
        var count = 0
        val compression = config.copy(model = "cheap-summary")
        val result = AgentModelClient.complete(config, "", AgentModelClient.ToolExecutor { error("tool") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", "facts".repeat(1000))), compactOnly = true,
            provider = provider { error("task provider must not compress") }, compactionTarget = {
                AgentCompactionTarget(compression, provider { request ->
                    count++
                    assertEquals("cheap-summary", request.effectiveConfig.model)
                    assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                    assertEquals(0, request.effectiveTools.length())
                    assertTrue(request.messages.toString().contains("CUSTOM_POLICY_KEEP_EVIDENCE"))
                    response("retained facts")
                }, "CUSTOM_POLICY_KEEP_EVIDENCE")
            })
        assertEquals(1, count)
        assertTrue(result.contextSnapshot!!.messages.first().contextSummary)
    }
    @Test fun summariesAboveFormerCharacterLimitsAreAccepted() {
        listOf(128_000, 1_000_000).forEach { window ->
            val text = "evidence ".repeat(2_000)
            val summarizer = AgentContextSummarizer(config.copy(contextWindow = window), provider { request ->
                assertFalse(request.messages.getJSONObject(0).getString("content").contains("输出上限"))
                response(text)
            }, AgentRunController(), false)
            assertEquals(text.trim(), summarizer.summarize(listOf(
                AgentModelClient.ConversationMessage("assistant", "facts".repeat(5_000)),
            )))
        }
    }
    @Test fun completeMultiMessageHistoryIsSentOnceWithoutMerge() {
        val history = (1..3).map { AgentModelClient.ConversationMessage("assistant", "FACT_$it " + "x".repeat(2200)) }
        val seen = mutableListOf<String>()
        val summarizer = AgentContextSummarizer(config, provider {
            seen += it.messages.getJSONObject(1).getString("content")
            response("all evidence")
        }, AgentRunController(), false)
        assertEquals("all evidence", summarizer.summarize(history))
        val expected = "待整理的历史：\n" + history.joinToString("") { AgentConversationCodec.toJsonObject(it).toString() + "\n" }
        assertEquals(listOf(expected), seen)
    }
    @Test fun smallModelDoesNotSplitHistoryThatWouldPreviouslyFitInSeveralRequests() {
        var calls = 0
        val summarizer = AgentContextSummarizer(config.copy(model = "small", contextWindow = 6000), provider {
            calls++
            response("not used")
        }, AgentRunController(), false)
        val failure = assertThrows(AgentModelFailure::class.java) {
            summarizer.summarize((1..3).map { AgentModelClient.ConversationMessage("assistant", "FACT_$it " + "x".repeat(2200)) })
        }
        assertEquals("COMPACTION_MODEL_CAPACITY", failure.code)
        assertTrue(failure.message.orEmpty().contains("一次性"))
        assertEquals(0, calls)
    }
    @Test fun oversizedSingleMessageFailsBeforeSending() {
        var calls = 0
        val summarizer = AgentContextSummarizer(config.copy(contextWindow = 4000), provider {
            calls++; response("not used")
        }, AgentRunController(), false)
        val failure = assertThrows(AgentModelFailure::class.java) {
            summarizer.summarize(listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(100_000))))
        }
        assertEquals("COMPACTION_MODEL_CAPACITY", failure.code)
        assertEquals(0, calls)
    }
}
