package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanModeLoopTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    private fun schema(name: String) = AgentToolSchema.function(name, "test", JSONObject().put("type", "object"))
    private inner class Scripted : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        val requests = mutableListOf<ProviderRequest>()
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            requests += request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
            return if (requests.size == 1) ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call("q", "ask_user_questions")).put(call("w", "terminal"))))
            else ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
        }
    }
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "stable")
    @Test fun unansweredQuestionBlocksSameBatchInNormalModeAndPairsToolResults() {
        val provider = Scripted(); val calls = mutableListOf<String>()
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            if (it.name == "ask_user_questions") AgentModelClient.ToolResult("""{"ok":false,"status":"unanswered"}""")
            else AgentModelClient.ToolResult("""{"ok":true}""")
        }, provider = provider, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")))
        assertEquals(listOf("ask_user_questions"), calls)
        assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == "w" && it.content.contains("QUESTION_TURN_BOUNDARY") })
        assertEquals(2, provider.requests.size)
    }
    @Test fun unansweredQuestionBlocksSameBatchEvenWhenApprovedPathIsUnavailable() {
        val provider = Scripted(); val controller = AgentRunController(); val calls = mutableListOf<String>()
        val gate = PlanRunGate("chat", "run", controller)
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            AgentModelClient.ToolResult("""{"ok":false,"status":"unanswered"}""")
        }, provider = provider, runController = controller, planGate = gate,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")))
        assertEquals(listOf("ask_user_questions"), calls)
        assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == "w" && it.content.contains("PLAN_QUESTION_BOUNDARY") })
    }
    @Test fun yieldableChildCannotStartBeforeGate() {
        val starts = AtomicInteger(); val controller = AgentRunController()
        val persistence = object : YieldableSubagents.Persistence {
            override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) = Unit
            override fun finished(taskId: String, report: String) = true
            override fun interrupted() = Unit
        }
        val owner = YieldableSubagents(controller, persistence) { _, _ -> starts.incrementAndGet(); AgentModelClient.ToolResult("{}") }
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            var n = 0
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                if (n++ == 0) ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(call("s", "subagent"))))
                else ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
        }
        try {
            val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("not expected") },
                provider = provider, runController = controller, planGate = PlanRunGate("chat", "run", controller),
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                additionalTools = JSONArray().put(schema("subagent")), yieldableSubagents = owner)
            assertEquals(0, starts.get())
            assertTrue(response.transcript.any { it.role == "tool" && it.content.contains("PLAN_APPROVAL_REQUIRED") })
        } finally { controller.cancel(); owner.close() }
    }
    @Test fun planProjectionKeepsExistingSystemHistoryAndSessionWhileNormalHasNoPlanSchema() {
        fun request(gate: PlanRunGate?): ProviderRequest {
            var captured: ProviderRequest? = null
            val provider = object : AgentProviderClient {
                override val id = "capture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    captured = request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
                }
            }
            val tools = JSONArray()
            if (gate != null) PlanRunGate.schemas(tools)
            AgentModelClient.complete(config, "new", AgentModelClient.ToolExecutor { error("no tools") },
                provider = provider, runController = AgentRunController(), planGate = gate, sessionId = "same-session",
                history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "old history")),
                additionalTools = tools, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) })
            return captured!!
        }
        val ordinary = request(null)
        val planned = request(PlanRunGate("chat", "run", AgentRunController()))
        assertEquals("same-session", ordinary.sessionId)
        assertEquals(ordinary.sessionId, planned.sessionId)
        assertEquals(ordinary.messages.length(), planned.messages.length())
        for (i in 0 until ordinary.messages.length() - 1)
            assertEquals(ordinary.messages.get(i).toString(), planned.messages.get(i).toString())
        assertFalse(ordinary.tools.toString().contains("plan_propose"))
        assertTrue(planned.tools.toString().contains("plan_propose"))
        assertTrue(planned.messages.toString().contains("计划模式运行约束"))
    }

    @Test fun answeredQuestionCannotBypassBoundaryViaParallelSubagents() {
        val calls = mutableListOf<String>()
        val provider = object : AgentProviderClient {
            override val id = "parallel-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            var count = 0
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                if (count++ == 0) ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call("q", "ask_user_questions"))
                        .put(call("s1", "subagent")).put(call("s2", "subagent"))))
                else ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
        }
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            AgentModelClient.ToolResult("""{"ok":true,"status":"answered"}""")
        }, provider = provider, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("subagent")))
        assertEquals(listOf("ask_user_questions"), calls)
        for (id in listOf("s1", "s2"))
            assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == id && it.content.contains("QUESTION_TURN_BOUNDARY") })
    }

    @Test fun answeredQuestionAlsoBlocksSameBatchWriteUntilNextModelTurn() {
        val provider = Scripted(); val calls = mutableListOf<String>()
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            if (it.name == "ask_user_questions") AgentModelClient.ToolResult("""{"ok":true,"status":"answered"}""")
            else AgentModelClient.ToolResult("""{"ok":true}""")
        }, provider = provider, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")))
        assertEquals(listOf("ask_user_questions"), calls)
        assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == "w" && it.content.contains("QUESTION_TURN_BOUNDARY") })
    }

    @Test fun failedQuestionAlsoBlocksSameBatchWrite() {
        val provider = Scripted(); val calls = mutableListOf<String>()
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            if (it.name == "ask_user_questions") throw IllegalStateException("fixture")
            else AgentModelClient.ToolResult("""{"ok":true}""")
        }, provider = provider, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")))
        assertEquals(listOf("ask_user_questions"), calls)
        assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == "w" && it.content.contains("QUESTION_TURN_BOUNDARY") })
    }

    @Test fun steeringArrivingAfterAnswerStillBlocksStaleSameBatchTool() {
        val provider = Scripted(); val controller = AgentRunController(); val calls = mutableListOf<String>()
        val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
            calls += it.name
            AgentModelClient.ToolResult("""{"ok":true,"status":"answered"}""")
        }, provider = provider, runController = controller,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            additionalTools = JSONArray().put(schema("ask_user_questions")).put(schema("terminal")),
            onEvent = { if (it is AgentEvent.ToolStarted && it.name == "terminal") controller.steer("换个方向") })
        assertEquals(listOf("ask_user_questions"), calls)
        assertTrue(response.transcript.any { it.role == "tool" && it.toolCallId == "w" && it.content.contains("QUESTION_TURN_BOUNDARY") })
        assertTrue(provider.requests[1].messages.toString().contains("换个方向"))
    }

}
