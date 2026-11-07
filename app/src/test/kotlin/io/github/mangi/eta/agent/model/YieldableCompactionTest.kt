package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YieldableCompactionTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture",
        systemPrompt = "fixed", contextWindow = 128_000, autoCompactionEnabled = true)
    // The task window still triggers compaction; the summary model must fit the complete history.
    private val summaryConfig = config.copy(contextWindow = 1_000_000)
    private fun answer(content: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", content).put("finish_reason", "stop"))
    private fun tool(id: String, name: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
    private fun call(value: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(value)))
    private val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)

    @Test fun fullFreshReportAndImageSteerReachChatBeforeCompaction() {
        val controller = AgentRunController()
        val childRelease = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val seenReport = CountDownLatch(1)
        val calls = AtomicInteger()
        val compactions = AtomicInteger()
        val report = "完整子报告".repeat(3000)
        val image = AgentModelClient.ModelImage("data:image/png;base64,aW1hZ2U=", "image/png", 5)
        val storage = object : YieldableSubagents.Persistence {
            override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) = Unit
            override fun finished(taskId: String, report: String) = true
            override fun interrupted() = Unit
        }
        val owner = YieldableSubagents(controller, storage) { _, start ->
            start("sub-1", "")
            check(childRelease.await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult(report)
        }
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = this@YieldableCompactionTest.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    assertEquals(summaryConfig.contextWindow, request.config.contextWindow)
                    assertEquals("full report must first reach chat", 0L, seenReport.count)
                    compactions.incrementAndGet()
                    return answer("已完成旧工作。")
                }
                assertEquals(config.contextWindow, request.config.contextWindow)
                onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120_000)))
                return when (calls.incrementAndGet()) {
                    1 -> call(tool("child", "subagent", """{"prompt":"任务","description":"检查"}"""))
                    2 -> {
                        assertTrue(request.messages.toString().contains(report))
                        assertTrue(request.messages.toString().contains(image.reference))
                        seenReport.countDown()
                        call(tool("own", "get_current_context", "{}"))
                    }
                    3 -> answer("完成")
                    else -> error("unexpected")
                }
            }
        }
        var failure: Throwable? = null
        val thread = Thread {
            try {
                AgentModelClient.complete(config, "开始", AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{\"ok\":true}") },
                    history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "历史".repeat(10_000)),
                        AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答".repeat(10_000))),
                    provider = provider, runController = controller, additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()),
                    compactionTarget = { AgentCompactionTarget(summaryConfig, provider) },
                    yieldableSubagents = owner, onTranscript = { transcript ->
                        if (accepted.count > 0 && transcript.any { it.role == "tool" && it.content.contains("accepted") }) {
                            controller.pause()
                            accepted.countDown()
                        }
                    })
            } catch (t: Throwable) { failure = t }
        }
        try {
            thread.start(); assertTrue(accepted.await(5, TimeUnit.SECONDS))
            // Keep the parent paused until both input and report have arrived at the same boundary.
            controller.pause(); assertTrue(controller.steer("新方向", listOf(image))); childRelease.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.decision() == YieldableSubagents.Decision.WAIT && System.nanoTime() < deadline) Thread.yield()
            assertEquals(YieldableSubagents.Decision.RESUME, owner.decision())
            controller.resume(); thread.join(5000); assertFalse(thread.isAlive)
            failure?.let { throw AssertionError(it) }
            assertEquals(3, calls.get()); assertTrue(compactions.get() >= 1)
        } finally { childRelease.countDown(); controller.cancel(); owner.close(); thread.join(5000) }
    }

    @Test fun geminiRunStartIsReanchoredOnRetainedInputAfterCompaction() {
        val first = AgentConversationCodec.userTextMessage("原任务")
        GeminiEphemeralState.markRunStart(first)
        val history = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(first).put(JSONObject().put("role", "assistant").put("content", "长历史".repeat(5000)))
            .put(AgentConversationCodec.userTextMessage("新引导"))
            .put(JSONObject().put("role", "assistant").put("content", "更多历史".repeat(5000)))
        val provider = object : AgentProviderClient {
            override val id = "summary"
            override val capabilities = this@YieldableCompactionTest.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = answer("先前任务摘要")
        }
        val compacted = AgentContextCompactor(summaryConfig, provider, AgentRunController()).compact(history, 1, emptySet())
        val users = (0 until compacted.length()).map(compacted::getJSONObject).filter { it.optString("role") == "user" }
        assertEquals(1, users.size); assertTrue(GeminiEphemeralState.isRunStart(users.single()))
        assertTrue(GeminiEphemeralState.isRunStart(first))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
        GeminiEphemeralState.attachSteps(assistant, JSONArray().put(JSONObject().put("type", "function_call")
            .put("id", "after-compact").put("name", "fixture")))
        compacted.put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "after-compact").put("content", "真实结果"))
        val input = GeminiInteractionHistory.input(compacted).toString()
        assertTrue(input.contains("after-compact")); assertTrue(input.contains("function_result"))
    }
}
