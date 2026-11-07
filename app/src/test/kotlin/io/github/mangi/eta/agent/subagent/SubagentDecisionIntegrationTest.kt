package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.subagentcontext.MailboxMemoryStorage
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs the real owner, child executor and both AgentLoops with scripted providers. */
class SubagentDecisionIntegrationTest {
    private class DecisionMemory : SubagentDecisionBroker.Storage {
        var records = JSONArray()
        override fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject = synchronized(this) {
            val copy = JSONArray(records.toString())
            val result = operation(copy)
            records = copy
            result
        }
    }
    private fun decisionEnvelope(text: String): List<JSONObject> {
        val array = JSONArray(text.substring(text.indexOf('[')))
        return (0 until array.length()).map(array::getJSONObject)
    }
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
        apiKey = "fixture", model = "gpt-fixture", systemPrompt = "stable-parent", terminalTools = true)
    private fun call(id: String, name: String, args: JSONObject) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tools(item: JSONObject, signed: Boolean = false): ProviderResponse {
        val message = JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
            .put("tool_calls", JSONArray().put(item))
        if (signed) AnthropicEphemeralState.attachContentBlocks(message, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "opaque-signature"))
            .put(JSONObject().put("type", "tool_use").put("id", item.getString("id"))
                .put("name", item.getJSONObject("function").getString("name"))
                .put("input", JSONObject(item.getJSONObject("function").getString("arguments")))))
        return ProviderResponse(message)
    }
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun provider(failure: AtomicReference<Throwable?>, script: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            try { script(request) } catch (t: Throwable) { failure.compareAndSet(null, t); throw t }
    }
    @Test fun sealedGroupWakesForCriticalRequestAndChildReceivesOriginalSignedToolResult() {
        val parent = AgentRunController(); val groupStore = MailboxMemoryStorage()
        val decisions = DecisionMemory()
        val broker = SubagentDecisionBroker("parent", parent, decisions, timeoutMs = 5000)
        lateinit var owner: YieldableSubagents
        val releaseChild = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val childRound = AtomicInteger(); val parentRound = AtomicInteger()
        val childModel = provider(failure) { request ->
            when (childRound.incrementAndGet()) {
                1 -> { check(releaseChild.await(3, TimeUnit.SECONDS))
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (owner.groupState("batch") != SubagentTaskGroup.State.SEALED && System.nanoTime() < until) Thread.sleep(5)
                    check(owner.groupState("batch") == SubagentTaskGroup.State.SEALED)
                    tools(call("actual-child-call", "ask_parent", JSONObject().put("request_id", "critical")
                    .put("question", "which path?").put("context_refs", JSONArray(listOf("evidence:1")))), signed = true) }
                2 -> {
                    val matched = (0 until request.messages.length()).map(request.messages::getJSONObject)
                        .single { it.optString("role") == "tool" && it.optString("tool_call_id") == "actual-child-call" }
                    assertEquals("chosen", JSONObject(matched.getString("content")).getString("response"))
                    assertTrue(request.messages.toString().contains("opaque-signature"))
                    val holders = (0 until request.messages.length()).map(request.messages::getJSONObject).filter { it.toString().contains("chosen") }
                    assertEquals(1, holders.size); assertEquals("tool", holders.single().getString("role"))
                    answer("task_status: completed\nsummary: done\nevidence: evidence:1\nremaining: -\nnext_action: -")
                }
                else -> error("unexpected child round")
            }
        }
        val worker = SubagentExecutor(conversationKey = "decision-${UUID.randomUUID()}", parentController = parent,
            parentRunId = "parent", configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected child tool") }, skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null, onProgress = {},
            mailboxStorage = groupStore, decisionBroker = broker, providerFactory = { childModel })
        owner = YieldableSubagents(parent, groupStore, parentRunId = "parent", decisionBroker = broker,
            executeGrouped = { call, invocation, started -> worker.execute(call.round, call, invocation, onStarted = started) }) { call, started ->
            worker.execute(call.round, call, onStarted = started)
        }
        val parentModel = provider(failure) { request ->
            when (parentRound.incrementAndGet()) {
                1 -> tools(call("launch", "subagent", JSONObject().put("prompt", "critical task").put("description", "task")
                    .put("subagent_type", "decision-fixture").put("tools", JSONArray(listOf("ask_parent"))).put("group_key", "batch")))
                2 -> { releaseChild.countDown(); answer("no more independent work") }
                3 -> {
                    val envelope = (0 until request.messages.length()).map(request.messages::getJSONObject)
                        .single { it.optString("content").contains("which path?") }
                    assertEquals("assistant", envelope.getString("role"))
                    val text = envelope.getString("content"); val data = decisionEnvelope(text).single()
                    tools(call("parent-response", "subagent_respond", JSONObject().put("request_id", data.getString("request_id"))
                        .put("task_id", data.getString("task_id")).put("invocation_id", data.getString("invocation_id"))
                        .put("tool_call_id", data.getString("tool_call_id")).put("action", "answer").put("response", "chosen")))
                }
                4, 5 -> if (request.messages.toString().contains("full_report_ref:")) answer("finished") else answer("waiting for report")
                else -> error("unexpected parent round")
            }
        }
        try {
            val schemas = JSONArray().also { AgentSubagentToolCatalog.appendSchemas(it, SubagentProfile.BUILTIN_PROFILES, true) }
            val result = AgentModelClient.complete(config, "parent task", AgentModelClient.ToolExecutor { broker.respond(it) },
                provider = parentModel, runController = parent, additionalTools = schemas, yieldableSubagents = owner,
                sessionId = "parent-session", operationId = "parent")
            failure.get()?.let { throw AssertionError("provider callback failed", it) }
            assertEquals("finished", result.content)
            assertEquals(2, childRound.get())
            assertEquals(1, result.transcript.count { it.messageId.startsWith("subagent-report-") })
        } finally {
            owner.close()
            failure.get()?.let { throw AssertionError("provider callback failed", it) }
        }
    }
    /** Both ungrouped children ask while the parent is inside a provider call, not at a wait. */
    @Test fun ungroupedRequestsWaitForSignedParentBoundaryAndKeepOriginalPairing() {
        listOf("anthropic", "gemini").forEach { signedKind ->
            val parent = AgentRunController()
            val store = MailboxMemoryStorage()
            val broker = SubagentDecisionBroker("parent-$signedKind", parent, DecisionMemory(), timeoutMs = 10000)
            val failure = AtomicReference<Throwable?>()
            val release = CountDownLatch(1)
            val childFinished = CountDownLatch(2)
            val childSequence = AtomicInteger()
            val parentRound = AtomicInteger()
            val stableTools = AtomicReference<String>()
            val stablePrefix = AtomicReference<String>()
            val cacheKey = AtomicReference<String>()
            val worker = SubagentExecutor(conversationKey = "ungrouped-${UUID.randomUUID()}", parentController = parent,
                parentRunId = "parent-$signedKind", configProvider = { config },
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected child tool") },
                skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
                mcpTools = JSONArray(), executionContext = null, onProgress = {}, mailboxStorage = store,
                decisionBroker = broker, providerFactory = {
                    val id = if (childSequence.incrementAndGet() == 1) "a" else "b"
                    val round = AtomicInteger()
                    provider(failure) { request ->
                        when (round.incrementAndGet()) {
                            1 -> {
                                check(release.await(4, TimeUnit.SECONDS))
                                tools(call("child-$id", "ask_parent", JSONObject().put("request_id", id)
                                    .put("question", "critical-question-$id")))
                            }
                            2 -> {
                                val results = (0 until request.messages.length()).map(request.messages::getJSONObject)
                                    .filter { it.optString("role") == "tool" && it.optString("tool_call_id") == "child-$id" }
                                assertEquals(1, results.size)
                                assertEquals("decision-$id", JSONObject(results.single().getString("content")).getString("response"))
                                childFinished.countDown()
                                answer("task_status: completed\nsummary: done\nevidence: fixture\nremaining: -\nnext_action: -")
                            }
                            else -> error("child $id unexpectedly continued")
                        }
                    }
                })
            val owner = YieldableSubagents(parent, store, parentRunId = "parent-$signedKind", decisionBroker = broker,
                executeOwned = { c, task, started -> worker.execute(c.round, c, decisionTaskId = task, onStarted = started) }) { c, started ->
                worker.execute(c.round, c, onStarted = started)
            }
            val model = provider(failure) { request ->
                assertEquals("stable-session-$signedKind", request.sessionId)
                when (parentRound.incrementAndGet()) {
                    1, 2 -> tools(call("launch-${parentRound.get()}", "subagent", JSONObject()
                        .put("prompt", "task ${parentRound.get()}").put("description", "ungrouped")
                        .put("subagent_type", "decision-fixture").put("tools", JSONArray(listOf("ask_parent")))))
                    3 -> {
                        stableTools.set(request.tools.toString())
                        stablePrefix.set(request.messages.getJSONObject(0).toString())
                        cacheKey.set(JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key"))
                        release.countDown()
                        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                        while (broker.pendingBatch().size < 2 && System.nanoTime() < until) Thread.sleep(5)
                        check(broker.pendingBatch().size == 2) { "both child requests must arrive during provider call" }
                        check(!request.messages.toString().contains("critical-question-"))
                        val response = tools(call("signed-parent-tool", "fixture_parent_tool", JSONObject()), signed = signedKind == "anthropic")
                        if (signedKind == "gemini") GeminiEphemeralState.attachSteps(response.assistantMessage,
                            JSONArray().put(JSONObject().put("type", "function_call").put("name", "fixture_parent_tool")
                                .put("thoughtSignature", "opaque-gemini-signature")))
                        response
                    }
                    4 -> {
                        assertEquals(stableTools.get(), request.tools.toString())
                        assertEquals(cacheKey.get(), JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key"))
                        assertEquals(stablePrefix.get(), request.messages.getJSONObject(0).toString())
                        assertFalse(request.messages.toString().contains("critical-question-"))
                        assertTrue(request.messages.toString().contains(if (signedKind == "anthropic") "opaque-signature" else "opaque-gemini-signature"))
                        val paired = (0 until request.messages.length()).map(request.messages::getJSONObject)
                            .filter { it.optString("role") == "tool" && it.optString("tool_call_id") == "signed-parent-tool" }
                        assertEquals(1, paired.size)
                        assertEquals("parent-tool-result", paired.single().getString("content"))
                        answer("parent complete boundary")
                    }
                    5 -> {
                        assertEquals(cacheKey.get(), JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key"))
                        val records = (0 until request.messages.length()).map(request.messages::getJSONObject)
                            .filter { it.optString("content").contains("critical-question-") }
                            .flatMap { m ->
                                assertEquals("assistant", m.getString("role"))
                                decisionEnvelope(m.getString("content"))
                            }
                        assertEquals(setOf("a", "b"), records.map { it.getString("request_id") }.toSet())
                        val calls = JSONArray()
                        records.forEach { r ->
                            val id = r.getString("request_id")
                            assertEquals("child-$id", r.getString("tool_call_id"))
                            calls.put(call("respond-$id", "subagent_respond", JSONObject()
                                .put("request_id", id).put("task_id", r.getString("task_id"))
                                .put("invocation_id", r.getString("invocation_id"))
                                .put("tool_call_id", r.getString("tool_call_id"))
                                .put("action", "answer").put("response", "decision-$id")))
                        }
                        ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                            .put("finish_reason", "tool_calls").put("tool_calls", calls))
                    }
                    6 -> {
                        check(childFinished.await(4, TimeUnit.SECONDS))
                        repeat(2) { checkNotNull(store.finished.poll(4, TimeUnit.SECONDS)) }
                        answer("waiting for child reports")
                    }
                    else -> if (parentRound.get() > 10) error("parent loop did not terminate")
                        else if (request.messages.toString().contains("full_report_ref:")) answer("finished")
                        else answer("waiting for child reports")
                }
            }
            try {
                val schemas = JSONArray().also {
                    AgentSubagentToolCatalog.appendSchemas(it, SubagentProfile.BUILTIN_PROFILES, true)
                    it.put(AgentToolSchema.function("fixture_parent_tool", "fixture", JSONObject().put("type", "object")))
                }
                val result = AgentModelClient.complete(config, "parent task", AgentModelClient.ToolExecutor { c ->
                    when (c.name) {
                        "fixture_parent_tool" -> AgentModelClient.ToolResult("parent-tool-result")
                        "subagent_respond" -> broker.respond(c)
                        else -> error("unexpected parent tool ${c.name}")
                    }
                }, provider = model, runController = parent, additionalTools = schemas, yieldableSubagents = owner,
                    sessionId = "stable-session-$signedKind", operationId = "parent-$signedKind")
                failure.get()?.let { throw AssertionError("provider callback failed", it) }
                assertEquals("finished", result.content)
                assertEquals(0L, childFinished.count)
                assertTrue("provider loop must be bounded", parentRound.get() <= 10)
            } finally {
                release.countDown()
                owner.close()
                failure.get()?.let { throw AssertionError("provider callback failed", it) }
            }
        }
    }

    @Test fun cancelDecisionStopsRealChildAfterPairedToolResultWithoutAnotherProviderRound() {
        val parent = AgentRunController(); val storage = DecisionMemory()
        val broker = SubagentDecisionBroker("cancel-run", parent, storage, timeoutMs = 5000)
        val failure = AtomicReference<Throwable?>(); val rounds = AtomicInteger()
        val key = "cancel-${UUID.randomUUID()}"; val childId = AtomicReference<String>()
        val model = provider(failure) {
            check(rounds.incrementAndGet() == 1) { "cancel must stop before another model round" }
            tools(call("real-ask", "ask_parent", JSONObject().put("request_id", "cancel-request").put("question", "blocker")))
        }
        val worker = SubagentExecutor(conversationKey = key, parentController = parent, parentRunId = "cancel-run",
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("no side effects") }, skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null,
            onProgress = {}, decisionBroker = broker, providerFactory = { model })
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val launch = AgentModelClient.ToolCall("launch", "subagent", JSONObject().put("prompt", "task")
                .put("description", "task").put("subagent_type", "decision-fixture")
                .put("tools", JSONArray(listOf("ask_parent"))).toString())
            val future = pool.submit(java.util.concurrent.Callable {
                worker.execute(1, launch, decisionTaskId = "task") { id, _ -> childId.set(id) }
            })
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            var batch = broker.pendingBatch()
            while (batch.isEmpty() && System.nanoTime() < until) { Thread.sleep(5); batch = broker.pendingBatch() }
            assertEquals(1, batch.size)
            val r = batch.single()
            val response = AgentModelClient.ToolCall("response", "subagent_respond", JSONObject()
                .put("request_id", r.getString("request_id")).put("task_id", r.getString("task_id"))
                .put("invocation_id", r.getString("invocation_id")).put("tool_call_id", r.getString("tool_call_id"))
                .put("action", "cancel").toString())
            assertTrue(JSONObject(broker.respond(response).content).getBoolean("ok"))
            val result = future.get(4, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError("provider callback failed", it) }
            assertEquals(1, rounds.get())
            val history = checkNotNull(SubagentSessionStore.get(key, childId.get())).history
            val paired = history.filter { it.role == "tool" && it.toolCallId == "real-ask" }
            assertEquals(1, paired.size)
            assertEquals("cancel", JSONObject(paired.single().content).getString("status"))
            assertNotEquals("completed", JSONObject(result.content).optString("task_status"))
        } finally {
            broker.close(); pool.shutdownNow()
            failure.get()?.let { throw AssertionError("provider callback failed", it) }
        }
    }

    @Test fun realChildPlanGateBlocksAskAndExplicitProfilePermissionsRemainEffective() {
        val base = SubagentProfile.resolve("decision-fixture", emptyList(), "fixture", listOf("ask_parent"))
        assertTrue(base.isToolAllowed("ask_parent"))
        assertFalse(base.copy(disallowedTools = setOf("ask_parent")).isToolAllowed("ask_parent"))
        assertFalse(base.copy(explicitTools = setOf("read_file")).isToolAllowed("ask_parent"))
        val parent = AgentRunController(); val gate = io.github.mangi.eta.local.planmode.PlanRunGate("fixture", "parent", parent)
        val memory = DecisionMemory()
        val broker = SubagentDecisionBroker("parent", parent, memory, timeoutMs = 3000)
        val failure = AtomicReference<Throwable?>(); val round = AtomicInteger()
        val model = provider(failure) { request ->
            when (round.incrementAndGet()) {
                1 -> tools(call("gated-ask", "ask_parent", JSONObject().put("request_id", "gated").put("question", "blocker")))
                2 -> {
                    val result = (0 until request.messages.length()).map(request.messages::getJSONObject)
                        .single { it.optString("role") == "tool" && it.optString("tool_call_id") == "gated-ask" }
                    assertEquals("PLAN_CHILD_BLOCKED", JSONObject(result.getString("content")).getString("code"))
                    answer("task_status: blocked\nsummary: blocked\nevidence: fixture\nremaining: -\nnext_action: -")
                }
                else -> error("unexpected child round")
            }
        }
        val worker = SubagentExecutor(conversationKey = "gate-${UUID.randomUUID()}", parentController = parent,
            parentRunId = "parent", configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected side effect") }, skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null,
            onProgress = {}, decisionBroker = broker, mailboxToolGate = { c, delegate -> gate.executeChild(c, delegate) },
            providerFactory = { model })
        try {
            val launch = AgentModelClient.ToolCall("launch", "subagent", JSONObject().put("prompt", "task")
                .put("description", "task").put("subagent_type", "decision-fixture")
                .put("tools", JSONArray(listOf("ask_parent"))).toString())
            worker.execute(1, launch, decisionTaskId = "task")
            failure.get()?.let { throw AssertionError("provider callback failed", it) }
            assertEquals(2, round.get())
            assertTrue(broker.pendingBatch().isEmpty())
            assertEquals(0, memory.records.length())
        } finally { gate.end(); broker.close(); parent.cancel(); failure.get()?.let { throw AssertionError("provider callback failed", it) } }
    }

}
