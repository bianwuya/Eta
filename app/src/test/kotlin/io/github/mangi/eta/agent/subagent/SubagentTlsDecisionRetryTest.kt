package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Final installed-line cross check: retries a model request, never a prior decision or effect. */
class SubagentTlsDecisionRetryTest {
    private class Memory : SubagentDecisionBroker.Storage {
        private var records = JSONArray()
        val pending = CountDownLatch(1)
        override fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject = synchronized(this) {
            val copy = JSONArray(records.toString())
            val result = operation(copy)
            records = copy
            if (copy.length() == 1 && copy.getJSONObject(0).optString("status") == "pending") pending.countDown()
            result
        }
        fun snapshot(): JSONArray = synchronized(this) { JSONArray(records.toString()) }
    }
    private fun tool(id: String, name: String, args: JSONObject = JSONObject()) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", "").put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(
            JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
                .put("name", name).put("arguments", args.toString())))))
    private fun matching(request: ProviderRequest, id: String) =
        (0 until request.messages.length()).map(request.messages::getJSONObject)
            .filter { it.optString("role") == "tool" && it.optString("tool_call_id") == id }

    @Test fun handshakeEofAfterDecisionAndCompletedEffectRetriesSameRequestWithoutReplayingEither() {
        val parent = AgentRunController()
        val storage = Memory()
        val broker = SubagentDecisionBroker("tls-parent", parent, storage, timeoutMs = 15000)
        val failures = AtomicReference<Throwable?>()
        val failedRequest = AtomicReference<ProviderRequest>()
        val rounds = AtomicInteger()
        val effects = AtomicInteger()
        val key = "tls-decision-${UUID.randomUUID()}"
        val childId = AtomicReference<String>()
        val provider = object : AgentProviderClient {
            override val id = "tls-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val round = rounds.incrementAndGet()
                if (round == 3) {
                    failedRequest.set(request)
                    throw SSLHandshakeException("Handshake failed").apply { initCause(EOFException("connection closed")) }
                }
                return try {
                    when (round) {
                        1 -> tool("real-ask", "ask_parent", JSONObject().put("request_id", "one-decision").put("question", "choose the approved path"))
                        2 -> {
                            val result = matching(request, "real-ask")
                            assertEquals(1, result.size)
                            assertEquals("chosen", JSONObject(result.single().getString("content")).getString("response"))
                            tool("completed-effect", "fixture_effect")
                        }
                        4 -> {
                            assertSame("TLS retries the same immutable provider request", failedRequest.get(), request)
                            assertEquals(1, matching(request, "real-ask").size)
                            val completed = matching(request, "completed-effect")
                            assertEquals(1, completed.size)
                            assertEquals("EFFECT_COMMITTED", completed.single().getString("content"))
                            assertEquals(1, effects.get())
                            assertEquals(1, storage.snapshot().length())
                            ProviderResponse(JSONObject().put("role", "assistant").put("finish_reason", "stop")
                                .put("content", "task_status: completed\nsummary: done\nevidence: effect committed once\nremaining: -\nnext_action: -"))
                        }
                        else -> error("unexpected provider request $round")
                    }
                } catch (failure: Throwable) { failures.compareAndSet(null, failure); throw failure }
            }
        }
        val worker = SubagentExecutor(conversationKey = key, parentController = parent, parentRunId = "tls-parent",
            configProvider = { AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
                apiKey = "fixture", model = "gpt-fixture", systemPrompt = "stable", terminalTools = true) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { c ->
                try {
                    assertEquals("completed-effect", c.id)
                    check(effects.incrementAndGet() == 1) { "completed effect replayed" }
                    AgentModelClient.ToolResult("EFFECT_COMMITTED")
                } catch (failure: Throwable) { failures.compareAndSet(null, failure); throw failure }
            }, skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray().put(AgentToolSchema.function("fixture_effect", "countable fixture", JSONObject().put("type", "object"))),
            executionContext = null, onProgress = {}, decisionBroker = broker, providerFactory = { provider })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val launch = AgentModelClient.ToolCall("launch", "subagent", JSONObject().put("prompt", "task").put("description", "tls integration")
                .put("subagent_type", "tls-fixture").put("tools", JSONArray(listOf("ask_parent", "fixture_effect"))).toString())
            val future = pool.submit(Callable {
                worker.execute(1, launch, decisionTaskId = "tls-task") { id, _ -> childId.set(id) }
            })
            assertTrue(storage.pending.await(5, TimeUnit.SECONDS))
            val request = storage.snapshot().getJSONObject(0)
            val response = AgentModelClient.ToolCall("reply", "subagent_respond", JSONObject()
                .put("request_id", request.getString("request_id")).put("task_id", request.getString("task_id"))
                .put("invocation_id", request.getString("invocation_id")).put("tool_call_id", request.getString("tool_call_id"))
                .put("action", "answer").put("response", "chosen").toString())
            assertTrue(JSONObject(broker.respond(response).content).getBoolean("ok"))
            val completed = future.get(12, TimeUnit.SECONDS)
            failures.get()?.let { throw AssertionError("fixture callback failed", it) }
            assertTrue(completed.content, JSONObject(completed.content).getBoolean("ok"))
            assertEquals(4, rounds.get()); assertEquals(1, effects.get())
            val record = storage.snapshot()
            assertEquals(1, record.length()); assertEquals("answer", record.getJSONObject(0).getString("status"))
            val history = checkNotNull(SubagentSessionStore.get(key, childId.get())).history
            assertEquals(1, history.count { it.role == "tool" && it.toolCallId == "real-ask" })
            assertEquals(1, history.count { it.role == "tool" && it.toolCallId == "completed-effect" })
        } finally {
            broker.close(); parent.cancel(); pool.shutdownNow()
            failures.get()?.let { throw AssertionError("fixture callback failed", it) }
        }
    }
}
