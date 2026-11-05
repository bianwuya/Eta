package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanSubagentPathTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    private fun schema(name: String) = AgentToolSchema.function(name, "fixture", JSONObject().put("type", "object"))
    private fun checkActualChild(guarded: Boolean, questionAnswer: String = "unanswered") {
        val parent = AgentRunController()
        val gate = PlanRunGate("chat", "run", parent)
        val delegated = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val calls = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "child-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests += request.messages.toString()
                return if (calls.getAndIncrement() == 0) ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call("q", "ask_user_questions")).put(call("w", "terminal"))))
                else ProviderResponse(JSONObject().put("role", "assistant").put("content", "child done").put("finish_reason", "stop"))
            }
        }
        val executor = SubagentExecutor(conversationKey = "fixture-$guarded", parentController = parent,
            configProvider = { AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "", terminalTools = true) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("child factory required") },
            childToolFactory = {
                val actual = AgentModelClient.ToolExecutor { c -> delegated += c.name
                    if (c.name == "ask_user_questions") AgentModelClient.ToolResult(
                        JSONObject().put("ok", questionAnswer == "answered").put("status", questionAnswer).toString())
                    else AgentModelClient.ToolResult("""{"ok":true}""")
                }
                if (!guarded) actual else AgentModelClient.ToolExecutor { c -> gate.executeChild(c) { actual.execute(c) } }
            },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")),
            executionContext = null, onProgress = {}, providerFactory = { provider })
        val start = AgentModelClient.ToolCall("child-call", "subagent", """{"prompt":"test","description":"test","subagent_type":"ephemeral-fixture","tools":["*"]}""")
        try {
            val result = executor.execute(1, start)
            assertTrue("child failed: ${result.content}", JSONObject(result.content).optBoolean("ok"))
            assertEquals(2, requests.size)
            assertTrue(requests.last().contains("QUESTION_TURN_BOUNDARY"))
            assertFalse(delegated.contains("terminal"))
            if (guarded) { assertTrue(requests.last().contains("PLAN_CHILD_BLOCKED")); assertTrue(delegated.isEmpty()) }
            else assertEquals(listOf("ask_user_questions"), delegated)
        } finally { parent.cancel() }
    }
    @Test fun normalActualChildRejectsSameBatchWriteAfterUnansweredQuestion() = checkActualChild(false)
    @Test fun normalActualChildRejectsSameBatchWriteAfterAnsweredQuestion() = checkActualChild(false, "answered")
    @Test fun planActualChildRejectsAskAndSameBatchWriteBeforeRouting() = checkActualChild(true)
}
