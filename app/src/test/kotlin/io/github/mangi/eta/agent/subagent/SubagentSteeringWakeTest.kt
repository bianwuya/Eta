package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentSteeringWakeTest {
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))

    @Test fun runtimeLossMarksRunningReceiptUnknownWithoutReplayingAnyTask() {
        val receipt = AgentModelClient.ConversationMessage(role = "tool", toolCallId = "child-1",
            content = JSONObject().put("ok", true).put("code", "SUBAGENT_RUNNING").put("status", "running").toString())
        val known = AgentModelClient.ConversationMessage(role = "tool", toolCallId = "child-2", content = "committed result")
        val recovered = AgentSubagentCheckpoint.interruptRunningReceipts(listOf(receipt, known))
        assertEquals("SUBAGENT_INTERRUPTED", JSONObject(recovered[0].content).getString("code"))
        assertFalse(JSONObject(recovered[0].content).getBoolean("ok"))
        assertEquals(known, recovered[1])
        assertEquals(recovered, AgentSubagentCheckpoint.interruptRunningReceipts(recovered))
    }

    @Test fun steeringWakesParentWhileOneOrTwoChildrenKeepRunningAndResultsArriveOnce() {
        for (count in listOf(1, 2)) {
            val controller = AgentRunController()
            val childrenStarted = CountDownLatch(count)
            val release = CountDownLatch(1)
            val awakened = CountDownLatch(1)
            val calls = AtomicInteger()
            val childCalls = AtomicInteger()
            val transcripts = CopyOnWriteArrayList<String>()
            val requests = CopyOnWriteArrayList<String>()
            val provider = object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    requests += request.messages.toString()
                    return when (calls.incrementAndGet()) {
                        1 -> ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                            .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().also { array ->
                                repeat(count) { i -> array.put(JSONObject().put("id", "child-$i").put("type", "function")
                                    .put("function", JSONObject().put("name", "subagent").put("arguments", "{}"))) }
                            }))
                        2 -> {
                            assertTrue(request.messages.toString().contains("respond while children work"))
                            assertEquals("children must still be running", 1L, release.count)
                            awakened.countDown()
                            answer("responded to guidance")
                        }
                        else -> answer("all child results received")
                    }
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<AgentModelClient.ModelResponse.Text> {
                AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
                    model = "fixture", systemPrompt = ""), "inspect",
                    AgentModelClient.ToolExecutor { call ->
                        childCalls.incrementAndGet()
                        childrenStarted.countDown()
                        val binding = controller.register { release.countDown() }
                        try {
                            check(release.await(10, TimeUnit.SECONDS))
                            controller.throwIfCancelled()
                            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("summary", "result-${call.id}").toString())
                        } finally { binding.close() }
                    }, provider = provider, runController = controller,
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()),
                    onTranscript = { transcripts += it.joinToString("\n") { m -> m.content + m.contentJson } })
            }
            try {
                assertTrue(childrenStarted.await(3, TimeUnit.SECONDS))
                assertTrue(controller.steer("respond while children work"))
                assertTrue("steering must reach the parent before a child finishes", awakened.await(2, TimeUnit.SECONDS))
                assertFalse(controller.isCancelled)
                assertFalse("the run must retain live children after the parent responds", future.isDone)
                release.countDown()
                val result = future.get(5, TimeUnit.SECONDS)
                assertEquals("all child results received", result.content)
                assertEquals(count, childCalls.get())
                val reports = result.transcript.filter { it.messageId.startsWith("subagent-result-") }
                assertEquals(count, reports.size)
                assertEquals(count, reports.map { it.messageId }.distinct().size)
                assertEquals(count, result.transcript.count { it.role == "tool" })
                assertTrue(transcripts.any { it.contains("SUBAGENT_RUNNING") })
                assertEquals(3, calls.get())
            } finally {
                release.countDown(); controller.cancel(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}
