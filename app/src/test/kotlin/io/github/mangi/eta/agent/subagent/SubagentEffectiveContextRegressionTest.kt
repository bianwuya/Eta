package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Structural checks only: a summarizer is allowed to omit directives, and is not an authority. */
class SubagentEffectiveContextRegressionTest {
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun tool(id: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(
            JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
                .put("name", "read_file").put("arguments", "{}")))))

    private fun runCase(failSummary: Boolean) {
        val parent = AgentRunController()
        val registry = SubagentControlRegistry()
        val conversation = "effective-${UUID.randomUUID()}"
        val callbackFailure = AtomicReference<Throwable?>()
        val requests = mutableListOf<ProviderRequest>()
        val calls = AtomicInteger()
        val compactions = AtomicInteger()
        var childId = ""
        var chat = 0
        fun revise(value: String) {
            val result = JSONObject(registry.execute(AgentModelClient.ToolCall(UUID.randomUUID().toString(), "subagent_control",
                JSONObject().put("action", "revise").put("subagent_id", childId).put("instruction", value).toString())).content)
            check(result.getBoolean("ok")) { "revision was not queued: $result" }
        }
        val provider = object : AgentProviderClient {
            override val id = "fixture-provider"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                                  onEvent: (ProviderEvent) -> Unit): ProviderResponse = try {
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    compactions.incrementAndGet()
                    if (failSummary) throw AgentModelFailure("FIXTURE_SUMMARY_FAILURE", false, "fixture")
                    // Deliberately omits goal and revisions: this is not a semantic model-quality test.
                    answer("Summary of completed exchanges; no operations should be repeated.")
                } else {
                    requests.add(request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString())))
                    when (++chat) {
                        1 -> tool("completed-effect")
                        2 -> { revise("V1: investigate the obsolete direction; retain all original constraints"); answer("first boundary") }
                        3 -> { revise("V2: supersede V1; investigate the current direction; retain constraint C"); answer("second boundary") }
                        4 -> { onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120_000))); tool("post-revise-read") }
                        5 -> answer(if (failSummary) "resumed" else "UNCOVERED_TAIL_AFTER_COMPACTION")
                        6 -> answer("resumed")
                        else -> error("unexpected chat request $chat")
                    }
                }
            } catch (failure: Throwable) {
                // Expected compaction failure is part of the fixture, not a swallowed assertion.
                if (!(failSummary && failure is AgentModelFailure && failure.code == "FIXTURE_SUMMARY_FAILURE"))
                    callbackFailure.compareAndSet(null, failure)
                throw failure
            }
        }
        val worker = SubagentExecutor(conversationKey = conversation, parentController = parent,
            controlRegistry = registry,
            configProvider = { AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
                apiKey = "fixture", model = "gpt-fixture", systemPrompt = "not child system", terminalTools = true,
                contextWindow = 128_000, autoCompactionEnabled = true) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { call ->
                calls.incrementAndGet()
                AgentModelClient.ToolResult(if (call.id == "completed-effect") "COMPLETED_ONCE" else "large data ".repeat(4000))
            }, skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {}, providerFactory = { provider })
        fun invoke(resume: Boolean): JSONObject {
            val args = JSONObject().put("description", "context regression")
                .put("prompt", if (resume) "CONTINUE_WITHOUT_REPLAY" else "TASK: accomplish GOAL_A")
            if (resume) args.put("resume", childId) else args.put("subagent_type", "context-fixture")
                .put("tools", JSONArray().put("read_file"))
                .put("handoff", JSONObject().put("goal", "GOAL_A").put("user_constraints", JSONArray().put("CONSTRAINT_C")))
            return JSONObject(worker.execute(1, AgentModelClient.ToolCall(UUID.randomUUID().toString(), "subagent", args.toString())) { id, _ -> childId = id }.content)
        }
        try {
            val first = invoke(false)
            callbackFailure.get()?.let { throw AssertionError("provider callback failed", it) }
            assertEquals(first.toString(), !failSummary, first.getBoolean("ok"))
            assertTrue("actual child compactor must run", compactions.get() >= 1)
            val entry = requireNotNull(SubagentSessionStore.get(conversation, childId))
            assertEquals(if (failSummary) 0 else 1, entry.compactionCount.get())
            val durable = entry.history
            if (!failSummary) assertTrue(durable.any { it.content.contains("UNCOVERED_TAIL_AFTER_COMPACTION") })
            val directive = durable.lastOrNull { it.content.contains("V2: supersede V1") }
            assertNotNull("latest parent revision must survive without relying on summary wording", directive)
            assertEquals("assistant", directive!!.role)
            assertTrue(directive.messageId.startsWith("parent-agent-"))
            assertTrue(directive.parentAgentDirective)
            val restored = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(durable))
            assertTrue(restored.single { it.messageId == directive.messageId }.parentAgentDirective)
            if (!failSummary) {
                val afterCompact = requests[4].messages.toString()
                assertTrue(afterCompact.contains("GOAL_A")); assertTrue(afterCompact.contains("CONSTRAINT_C"))
                assertTrue(afterCompact.contains("V2: supersede V1"))
                assertFalse(afterCompact.contains("V1: investigate the obsolete direction"))
            }
            assertTrue(invoke(true).getBoolean("ok"))
            callbackFailure.get()?.let { throw AssertionError("provider callback failed", it) }
            val resumed = requests.last()
            val text = resumed.messages.toString()
            assertTrue(text.contains("GOAL_A")); assertTrue(text.contains("CONSTRAINT_C"))
            assertTrue(text.contains("V2: supersede V1"))
            if (!failSummary) assertTrue(text.contains("UNCOVERED_TAIL_AFTER_COMPACTION"))
            val revised = (0 until resumed.messages.length()).map(resumed.messages::getJSONObject)
                .single { it.optString("content").contains("V2: supersede V1") }
            assertEquals("user", revised.getString("role"))
            assertTrue(revised.optBoolean("_eta_runtime_parent_directive"))
            if (!failSummary) {
                assertFalse(text.contains("large data ".repeat(100)))
                assertFalse("superseded revision must not be reintroduced as active history", text.contains("V1: investigate the obsolete direction"))
            } else assertTrue(text.contains("COMPLETED_ONCE"))
            assertEquals(2, calls.get())
            val initial = requests.first()
            fun systems(request: ProviderRequest) = (0 until request.messages.length()).map(request.messages::getJSONObject)
                .filter { it.optString("role") == "system" }.map { it.toString() }
            assertEquals(systems(initial), systems(resumed))
            assertEquals(initial.tools.toString(), resumed.tools.toString())
            assertEquals(initial.sessionId, resumed.sessionId)
            assertEquals(initial.config.providerId, resumed.config.providerId)
            assertEquals(initial.config.providerType, resumed.config.providerType)
            assertEquals(initial.config.model, resumed.config.model)
            assertEquals(initial.config.authMode, resumed.config.authMode)
            assertEquals(initial.accountBinding, resumed.accountBinding)
            fun cacheKey(request: ProviderRequest) = JSONObject().also { OpenAiPromptCache.apply(it, request) }.optString("prompt_cache_key")
            assertTrue(cacheKey(initial).isNotBlank())
            assertEquals(cacheKey(initial), cacheKey(resumed))
        } finally { parent.cancel() }
    }

    @Test fun realChildCompactionThenResumeKeepsEffectiveContext() = runCase(false)
    @Test fun failedChildCompactionThenResumeKeepsOriginalContext() = runCase(true)
}
