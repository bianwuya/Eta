package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.subagent.*
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.subagentstats.SubagentRequestStat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanRecentFixesIntegrationChildTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    private fun tools(vararg calls: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls.toList())))
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun schema(name: String) = AgentToolSchema.function(name, "fixture", JSONObject().put("type", "object"))
    private val config = AgentModelClient.ModelConfig(providerId = "child-provider", baseUrl = "https://example.invalid",
        apiKey = "fixture", model = "child-model", systemPrompt = "parent-system-must-not-leak", terminalTools = true,
        contextWindow = 100_000, autoCompactionEnabled = true)
    private fun invocation(id: String, resume: String? = null) = AgentModelClient.ToolCall(id, "subagent", JSONObject()
        .put("prompt", "read only, retain exact guard").put("description", "integration child")
        .apply {
            if (resume == null) put("subagent_type", "integration-ephemeral").put("tools", JSONArray().put("*"))
            else put("resume", resume)
        }.toString())
    private fun control(registry: SubagentControlRegistry, action: String, id: String? = null, instruction: String? = null): JSONObject =
        JSONObject(registry.execute(AgentModelClient.ToolCall("control-$action", "subagent_control", JSONObject()
            .put("action", action).apply { id?.let { put("subagent_id", it) }; instruction?.let { put("instruction", it) } }
            .toString())).content)
    private fun provider(script: (ProviderRequest, AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "child-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            script(request, runController, onEvent)
    }

    @Test fun realStatsWrappedChildResumeRetainsPlanGuardControlIdentityAndSuccessfulCompactionCount() {
        val parent = AgentRunController()
        val gate = PlanRunGate("child-chat", "child-parent-run", parent)
        val registry = SubagentControlRegistry()
        val conversation = "plan-recent-child-${UUID.randomUUID()}"
        val stats = mutableListOf<SubagentRequestStat>()
        val requests = mutableListOf<ProviderRequest>()
        val delegated = mutableListOf<String>()
        val factories = AtomicInteger()
        val closed = AtomicInteger()
        val summaries = AtomicInteger()
        val rounds = AtomicInteger()
        var childId = ""
        val summaryProvider = provider { request, _, _ ->
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            summaries.incrementAndGet(); answer("retained concise history")
        }
        val childProvider = provider { request, childController, events ->
            requests += request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
            assertNotSame(parent, childController)
            assertFalse(request.config.subagentTools)
            assertFalse(request.config.systemPrompt.contains("parent-system-must-not-leak"))
            when (rounds.incrementAndGet()) {
                1 -> {
                    events(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 90_000, contextTokens = 90_000)))
                    answer("inspection result ".repeat(1000))
                }
                2 -> {
                    assertTrue(request.messages.toString().contains("retained concise history"))
                    val active = control(registry, "list").getJSONArray("active")
                    assertEquals(1, active.length())
                    assertEquals(childId, active.getJSONObject(0).getString("subagent_id"))
                    assertEquals("resume", active.getJSONObject(0).getString("tool_call_id"))
                    assertEquals("pause_requested", control(registry, "pause", childId).getString("status"))
                    assertTrue(childController.isPaused)
                    assertEquals("resume_requested", control(registry, "resume", childId).getString("status"))
                    assertFalse(childController.isPaused)
                    assertEquals("revision_queued", control(registry, "revise", childId, "inspect current logs, no writes").getString("status"))
                    tools(call("stale-read", "read_file"), call("stale-write", "terminal"))
                }
                3 -> {
                    assertTrue(request.messages.toString().contains("SUBAGENT_REPLAN_REQUIRED"))
                    val directive = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                        .single { it.optBoolean("_eta_parent_agent_directive") }
                    assertTrue(directive.getBoolean("_eta_runtime_parent_directive"))
                    assertTrue(directive.getString("content").contains("不是终端用户发言"))
                    tools(call("current-read", "read_file"), call("current-write", "terminal"))
                }
                4 -> {
                    assertTrue(request.messages.toString().contains("PLAN_CHILD_BLOCKED"))
                    answer("read-only resumed report")
                }
                else -> error("unexpected child request")
            }
        }
        val executor = SubagentExecutor(conversationKey = conversation, parentController = parent,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("child factory must retain ownership") },
            childToolFactory = {
                factories.incrementAndGet()
                object : AgentModelClient.ToolExecutor, AutoCloseable {
                    override fun execute(call: AgentModelClient.ToolCall) = gate.executeChild(call) {
                        delegated += call.id; AgentModelClient.ToolResult("""{"ok":true}""")
                    }
                    override fun close() { closed.incrementAndGet() }
                }
            }, skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray().put(schema("read_file")).put(schema("terminal")), executionContext = null,
            onProgress = {}, onRequestStat = { stats += it }, parentRunId = "child-parent-run",
            requestStatsEpoch = { "enabled-test" }, controlRegistry = registry,
            compactionTarget = { AgentCompactionTarget(it, summaryProvider) }, providerFactory = { childProvider })
        try {
            val first = executor.execute(1, invocation("first"))
            assertTrue(JSONObject(first.content).getBoolean("ok"))
            childId = JSONObject(first.content).getString("subagent_id")
            val initialSnapshot = requireNotNull(SubagentActivitySnapshot.decode(first.command))
            assertEquals(1, summaries.get()); assertEquals(1, initialSnapshot.compactionCount)
            assertNull(initialSnapshot.contextTokens)
            assertEquals(0, control(registry, "list").getInt("count"))
            val resumed = executor.execute(2, invocation("resume", childId))
            assertTrue(JSONObject(resumed.content).getBoolean("ok"))
            val snapshot = requireNotNull(SubagentActivitySnapshot.decode(resumed.command))
            assertEquals(childId, snapshot.subagentId); assertTrue(snapshot.resumed)
            assertEquals(1, snapshot.compactionCount); assertNull(snapshot.contextTokens)
            assertEquals(listOf("current-read"), delegated)
            assertEquals(2, factories.get()); assertEquals(2, closed.get())
            assertEquals(0, control(registry, "list").getInt("count"))
            assertEquals(listOf("$conversation-$childId"), requests.map { it.sessionId }.distinct())
            val finalStats = stats.filter { it.status == "completed" }
            assertTrue(finalStats.any { it.invocationId == "first" && it.purpose == "COMPACTION" && !it.resumed })
            assertEquals(3, finalStats.count { it.invocationId == "resume" && it.purpose == "CHAT" && it.resumed })
            assertTrue(finalStats.all { it.subagentId == childId && it.parentRunId == "child-parent-run" })
            assertFalse(parent.isCancelled)
        } finally { parent.cancel() }
    }

    @Test fun statsProviderDoesNotTurnExplicitChildCancellationIntoSuccessOrAllowResume() {
        val parent = AgentRunController()
        val registry = SubagentControlRegistry()
        val stats = mutableListOf<SubagentRequestStat>()
        val calls = AtomicInteger()
        var childId = ""
        val childProvider = provider { _, childController, _ ->
            calls.incrementAndGet()
            childId = control(registry, "list").getJSONArray("active").getJSONObject(0).getString("subagent_id")
            assertEquals("cancel_requested", control(registry, "cancel", childId).getString("status"))
            assertTrue(childController.isCancelled)
            answer("must not become a successful completion")
        }
        val executor = SubagentExecutor(conversationKey = "plan-recent-cancel-${UUID.randomUUID()}",
            parentController = parent, configProvider = { config },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("no tools") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {},
            onRequestStat = { stats += it }, requestStatsEpoch = { "enabled-test" },
            controlRegistry = registry, providerFactory = { childProvider })
        try {
            val failure = runCatching { executor.execute(1, invocation("cancelled")) }.exceptionOrNull()
            assertTrue("actual child cancellation propagates, not a synthetic successful result", failure is AgentRunCancelledException)
            assertEquals("SUBAGENT_CANCELLED_FINAL", JSONObject(executor.execute(2, invocation("resume", childId)).content).getString("code"))
            assertEquals(1, calls.get()); assertEquals(0, control(registry, "list").getInt("count"))
            assertFalse(parent.isCancelled)
        } finally { parent.cancel() }
    }
}
