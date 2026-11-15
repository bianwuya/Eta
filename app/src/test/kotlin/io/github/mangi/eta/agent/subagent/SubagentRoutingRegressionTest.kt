package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentRoutingRegressionTest {
    private val first = AgentModelClient.ModelConfig(providerId = "routing", baseUrl = "https://fixture.invalid/v1",
        apiKey = "fixture", model = "large-rejected", systemPrompt = "", contextWindow = 1_000_000,
        autoCompactionEnabled = true)
    private val next = first.copy(model = "small-compatible", contextWindow = 100_000)

    @Test fun fallbackUpdatesCompactionModelAndWindowBeforeTheNextToolTurn() {
        val seen = mutableListOf<Pair<String, ProviderRequestPurpose>>()
        val compactTargets = mutableListOf<String>()
        var rounds = 0
        val factory: (AgentModelClient.ModelConfig) -> AgentProviderClient = { config ->
            object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = OpenAiChatCompletionsProvider.capabilities
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    seen += request.config.model to request.purpose
                    if (config.model == first.model) throw AgentModelFailure("HTTP_404", false, "fixture unavailable")
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) return answer("full summary")
                    if (++rounds == 1) {
                        onEvent(ProviderEvent.Usage(AgentTokenUsage(contextTokens = 90_000, inputTokens = 88_000)))
                        return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                            .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                                .put("id", "read").put("type", "function").put("function", JSONObject()
                                    .put("name", "read_file").put("arguments", "{}")))))
                    }
                    return answer("done")
                }
            }
        }
        val executor = executor(listOf(first, next), factory, compaction = { active ->
            compactTargets += active.model
            AgentCompactionTarget(active, factory(active))
        })
        val result = executor.execute(1, call("fallback"))
        assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
        assertEquals(listOf(next.model), compactTargets)
        assertEquals(listOf(first.model to ProviderRequestPurpose.CHAT, next.model to ProviderRequestPurpose.CHAT,
            next.model to ProviderRequestPurpose.COMPACTION, next.model to ProviderRequestPurpose.CHAT), seen)
        val snapshot = requireNotNull(SubagentActivitySnapshot.decode(result.command))
        assertEquals(next.contextWindow, snapshot.contextWindow)
        assertEquals(1, snapshot.compactionCount)
    }

    @Test fun filteringTheFirstCandidateUsesActualInitialConfigAndRejectsEmptyListBeforeStart() {
        val short = first.copy(model = "too-short", contextWindow = 1)
        val seen = mutableListOf<String>()
        val factory: (AgentModelClient.ModelConfig) -> AgentProviderClient = { selected -> object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse { seen += selected.model; return answer("done") }
        } }
        val result = executor(listOf(short, next), factory).execute(1, call("filtered"))
        assertEquals(listOf(next.model), seen)
        assertEquals(next.contextWindow, SubagentActivitySnapshot.decode(result.command)?.contextWindow)
        var started = false
        val rejected = executor(listOf(short, short.copy(model = "also-short")), factory)
            .execute(1, call("empty")) { _, _ -> started = true }
        assertFalse(started)
        assertFalse(JSONObject(rejected.content).getBoolean("ok"))
        assertEquals(1, seen.size)
    }

    @Test fun completedChildResumeKeepsOriginalModelAndRejectsAnUnavailableBinding() {
        var candidates = listOf(first, next)
        val seen = mutableListOf<String>()
        val key = "resume-binding-${java.util.UUID.randomUUID()}"
        val executor = SubagentExecutor(
            conversationKey = key, parentController = AgentRunController(),
            configProvider = { candidates.first() }, candidateConfigs = { candidates },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = { AgentModelClient.ToolResult("fixture read") }, skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null,
            onProgress = {}, providerFactory = { selected -> object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = OpenAiChatCompletionsProvider.capabilities
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    seen += selected.model
                    return answer("done")
                }
            } })
        val initial = executor.execute(1, call("initial"))
        assertTrue(initial.content, JSONObject(initial.content).getBoolean("ok"))
        val childId = requireNotNull(SubagentActivitySnapshot.decode(initial.command)).subagentId
        fun resume(id: String) = AgentModelClient.ToolCall(id, "subagent", JSONObject()
            .put("resume", childId).put("prompt", "continue fixture")
            .put("description", "binding regression").toString())

        candidates = listOf(next, first)
        val continued = executor.execute(2, resume("continued"))
        assertTrue(continued.content, JSONObject(continued.content).getBoolean("ok"))
        assertEquals(listOf(first.model, first.model), seen)

        candidates = listOf(next)
        val rejected = executor.execute(3, resume("missing-binding"))
        assertFalse(JSONObject(rejected.content).getBoolean("ok"))
        assertEquals("unavailable bound history must never be sent to another model", 2, seen.size)
    }

    private fun executor(candidates: List<AgentModelClient.ModelConfig>, factory: (AgentModelClient.ModelConfig) -> AgentProviderClient,
        compaction: ((AgentModelClient.ModelConfig) -> AgentCompactionTarget)? = null) = SubagentExecutor(
        conversationKey = "routing-${java.util.UUID.randomUUID()}", parentController = AgentRunController(),
        configProvider = { candidates.first() }, candidateConfigs = { candidates },
        capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        baseToolExecutor = { AgentModelClient.ToolResult("fixture read") }, skillContext = SkillContext.EMPTY,
        memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null,
        onProgress = {}, providerFactory = factory, compactionTarget = compaction)

    private fun call(id: String) = AgentModelClient.ToolCall(id, "subagent", JSONObject()
        .put("subagent_type", "coder").put("prompt", "inspect fixture").put("description", "routing regression").toString())
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
}
