package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubagentIntegratedHistoryDecisionTest {
    @After fun close() = EtaDatabase.closeForTests()

    @Test fun cancelDecisionPersistsRealPairAndCancelledDisplay() = runBlocking {
        val stored = CountDownLatch(1)
        val storage = object : SubagentDecisionBroker.Storage {
            var records = JSONArray()
            override fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject = synchronized(this) {
                val copy = JSONArray(records.toString())
                val result = operation(copy)
                records = copy
                if ((0 until copy.length()).any { copy.getJSONObject(it).optString("status") == "pending" }) stored.countDown()
                result
            }
            fun snapshot(): JSONObject = synchronized(this) { JSONObject(records.getJSONObject(0).toString()) }
        }
        val parent = AgentRunController()
        val broker = SubagentDecisionBroker("history-run", parent, storage, timeoutMs = 5000)
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val key = "history-decision-${UUID.randomUUID()}"
        val childId = AtomicReference<String>()
        val callbackFailure = AtomicReference<Throwable?>()
        val rounds = AtomicInteger(); val effects = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = try {
                check(rounds.incrementAndGet() == 1) { "unexpected second provider call" }
                val ask = JSONObject().put("id", "real-ask").put("type", "function").put("function", JSONObject()
                    .put("name", "ask_parent").put("arguments", JSONObject().put("request_id", "critical")
                        .put("question", "blocker").toString()))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(ask)))
            } catch (t: Throwable) { callbackFailure.compareAndSet(null, t); throw t }
        }
        val executor = SubagentExecutor(conversationKey = key, parentController = parent, parentRunId = "history-run",
            configProvider = { AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
                apiKey = "fixture", model = "gpt-fixture", systemPrompt = "", terminalTools = true) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { effects.incrementAndGet(); error("unexpected side effect") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {}, decisionBroker = broker, providerFactory = { provider },
            detailStoreFactory = { store })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val launch = AgentModelClient.ToolCall("history-launch", "subagent", JSONObject().put("prompt", "task")
                .put("description", "task").put("subagent_type", "decision-fixture")
                .put("tools", JSONArray(listOf("ask_parent"))).toString())
            val future = pool.submit(Callable { executor.execute(1, launch, decisionTaskId = "task") { id, _ -> childId.set(id) } })
            assertTrue(stored.await(4, TimeUnit.SECONDS))
            val r = storage.snapshot()
            val response = AgentModelClient.ToolCall("respond", "subagent_respond", JSONObject()
                .put("request_id", r.getString("request_id")).put("task_id", r.getString("task_id"))
                .put("invocation_id", r.getString("invocation_id")).put("tool_call_id", r.getString("tool_call_id"))
                .put("action", "cancel").toString())
            assertTrue(JSONObject(broker.respond(response).content).getBoolean("ok"))
            val result = future.get(4, TimeUnit.SECONDS)
            callbackFailure.get()?.let { throw AssertionError("provider callback failed", it) }
            assertEquals(1, rounds.get()); assertEquals(0, effects.get())
            val history = checkNotNull(SubagentSessionStore.get(key, childId.get())).history
            assertEquals(1, history.count { it.role == "assistant" && it.toolCallsJson.contains("real-ask") })
            val paired = history.filter { it.role == "tool" && it.toolCallId == "real-ask" }
            assertEquals(1, paired.size)
            assertEquals("cancel", JSONObject(paired.single().content).getString("status"))
            assertNotEquals("completed", JSONObject(result.content).optString("task_status"))
            val reopened = SubagentDetailStore(context)
            val entries = reopened.history(launch.id)
            val tools = entries.filter { it.kind == "tool" }
            assertEquals(1, tools.size)
            val body = reopened.page(launch.id, "history-${tools.single().id}", 0).orEmpty()
            assertTrue(body.contains("cancel")); assertTrue(body.contains("critical"))
            assertEquals("cancelled", entries.single { it.kind == "terminal" }.status)
        } finally {
            broker.close(); pool.shutdownNow()
            callbackFailure.get()?.let { throw AssertionError("provider callback failed", it) }
            store.clear("history-launch")
        }
    }
}
