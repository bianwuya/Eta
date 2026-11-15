package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCancellationBoundaryTest {
    @Test fun interruptedToolBatchReturnsPairedTranscriptWithoutReplayingTools() {
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "fixture_read").put("parameters", JSONObject().put("type", "object"))))
        val calls = JSONArray().put(JSONObject().put("id", "pending").put("type", "function")
            .put("function", JSONObject().put("name", "fixture_read").put("arguments", "{}")))
        var toolExecutions = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "inspect", { toolExecutions++; error("must not run") },
                additionalTools = tools,
                provider = provider { ProviderResponse(JSONObject().put("role", "assistant")
                    .put("content", "").put("tool_calls", calls).put("finish_reason", "tool_calls")) },
                onEvent = { if (it is AgentEvent.ToolStarted) throw IllegalStateException("fixture interrupted callback") })
        }
        assertEquals(0, toolExecutions)
        val tool = failure.transcript.single { it.role == "tool" }
        assertEquals("pending", tool.toolCallId)
        assertTrue(tool.content.contains("TOOL_INTERRUPTED"))
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "stable",
    )
    private fun answer() = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "natural answer").put("finish_reason", "stop"))
    private fun provider(script: () -> ProviderResponse) = object : AgentProviderClient {
        override val id = "cancellation-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = script()
    }
    private fun assertCancelled(failure: Throwable?) {
        assertNotNull("cancel must terminate instead of succeeding", failure)
        assertTrue(failure is AgentModelExecutionException && failure.cause is AgentRunCancelledException)
    }

    @Test fun cancellationAfterPublishingAnswerWithoutChildrenCannotReturnSuccess() {
        val controller = AgentRunController()
        val finished = AtomicInteger()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "inspect", { error("no tools") }, provider = provider(::answer),
                runController = controller, onTranscript = { controller.cancel() },
                onEvent = { if (it is AgentEvent.RunFinished) finished.incrementAndGet() })
        }
        assertCancelled(failure)
        assertEquals(0, finished.get())
    }

    @Test fun cancellationInsideFinalEventCannotReturnASuccessResponse() {
        val controller = AgentRunController()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "inspect", { error("no tools") }, provider = provider(::answer),
                runController = controller, onEvent = { if (it is AgentEvent.RunFinished) controller.cancel() })
        }
        assertCancelled(failure)
    }

    @Test fun cancellationDuringFinalCompactionCannotReturnSuccess() = finalCompactionBoundary(cancel = true)

    @Test fun nonCancellationFinalCompactionFailureStillDeliversTheFullAnswer() = finalCompactionBoundary(cancel = false)

    private fun finalCompactionBoundary(cancel: Boolean) {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val summaries = AtomicInteger()
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "previous task $turn"),
            AgentModelClient.ConversationMessage("assistant", "verified fact $turn ".repeat(2200)),
        ) }
        val model = object : AgentProviderClient {
            override val id = "final-compaction-cancel-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    assertEquals(1_000_000, request.config.contextWindow)
                    summaries.incrementAndGet()
                    if (cancel) controller.cancel()
                    throw AgentModelFailure("FIXTURE_SUMMARY_FAILURE", false, "fixture summary failure")
                }
                assertEquals(50_000, request.config.contextWindow)
                onEvent(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 45_000)))
                return answer()
            }
        }
        fun complete() = AgentModelClient.complete(config.copy(contextWindow = 50_000), "inspect", { error("no tools") },
            provider = model, history = history, runController = controller, onEvent = events::add,
            compactionTarget = { AgentCompactionTarget(config.copy(contextWindow = 1_000_000), model) })
        if (cancel) {
            assertCancelled(assertThrows(AgentModelExecutionException::class.java) { complete() })
            assertEquals(0, events.count { it is AgentEvent.RunFinished })
        } else {
            val result = complete()
            assertEquals("natural answer", result.content)
            assertEquals("natural answer", result.contextSnapshot!!.messages.last().content)
            assertTrue(events.any { it is AgentEvent.ContextCompaction && it.reasonCode == "FIXTURE_SUMMARY_FAILURE" })
            assertEquals(1, events.count { it is AgentEvent.RunFinished })
        }
        assertEquals("fixture must actually enter final compaction", 1, summaries.get())
    }

    @Test fun completedModelCallDoesNotSealControllerForLaterCleanupOrReuse() {
        val controller = AgentRunController()
        val cleanup = AtomicInteger()
        val binding = controller.register { cleanup.incrementAndGet() }
        try {
            repeat(2) {
                val result = AgentModelClient.complete(config, "inspect", { error("no tools") },
                    provider = provider(::answer), runController = controller)
                assertEquals("natural answer", result.content)
                assertFalse(controller.isCancelled)
            }
            controller.cancel()
            assertTrue(controller.isCancelled)
            assertEquals(1, cleanup.get())
        } finally { binding.close(); controller.cancel() }
    }

}
