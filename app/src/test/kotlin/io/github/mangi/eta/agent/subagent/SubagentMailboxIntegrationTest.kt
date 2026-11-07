package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.planmode.PlanRunGate
import io.github.mangi.eta.local.subagentcontext.MailboxMemoryStorage
import io.github.mangi.eta.local.subagentcontext.SubagentMailbox
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentMailboxIntegrationTest {
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
        apiKey = "fixture", model = "gpt-fixture", systemPrompt = "stable-parent", terminalTools = true)
    private fun call(id: String, name: String, args: JSONObject) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tools(vararg calls: JSONObject, signed: Boolean = false): ProviderResponse {
        val assistant = JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
            .put("tool_calls", JSONArray(calls.toList()))
        if (signed) AnthropicEphemeralState.attachContentBlocks(assistant,
            JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "opaque-signature"))
                .apply { calls.forEach { item -> put(JSONObject().put("type", "tool_use").put("id", item.getString("id"))
                    .put("name", item.getJSONObject("function").getString("name"))
                    .put("input", JSONObject(item.getJSONObject("function").getString("arguments")))) } })
        return ProviderResponse(assistant)
    }
    private fun answer(text: String = "done") = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun args(prompt: String, group: Boolean = true) = JSONObject().put("prompt", prompt).put("description", prompt)
        .put("subagent_type", "mailbox-fixture").put("tools", JSONArray(listOf("agent_mailbox", "read_file")))
        .also { if (group) it.put("group_key", "batch") }
    private fun provider(failure: AtomicReference<Throwable?>? = null, script: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            try { script(request) } catch (thrown: Throwable) {
                failure?.compareAndSet(null, thrown)
                throw thrown
            }
    }
    private fun executor(parent: AgentRunController, store: SubagentMailbox.Storage?, model: AgentProviderClient,
        ordinary: AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { error("unexpected ordinary tool") },
        gate: PlanRunGate? = null, conversation: String = "mailbox-${UUID.randomUUID()}", registry: SubagentControlRegistry? = null,
        childConfig: AgentModelClient.ModelConfig = config,
        compactionTarget: ((AgentModelClient.ModelConfig) -> AgentCompactionTarget)? = null) =
        SubagentExecutor(conversationKey = conversation, parentController = parent, parentRunId = "parent",
            configProvider = { childConfig }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = ordinary, skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {}, mailboxStorage = store, controlRegistry = registry,
            mailboxToolGate = { tool, delegate -> if (gate == null) delegate() else gate.executeChild(tool, delegate) },
            compactionTarget = compactionTarget,
            providerFactory = { model })
    private fun toolResult(request: ProviderRequest, id: String) = (0 until request.messages.length()).map(request.messages::getJSONObject)
        .single { it.optString("role") == "tool" && it.optString("tool_call_id") == id }
    private fun remember(target: MutableList<ProviderRequest>, request: ProviderRequest) {
        target += request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
    }
    private fun keys(requests: List<ProviderRequest>) = requests.map { request -> JSONObject().also {
        OpenAiPromptCache.apply(it, request)
    }.getString("prompt_cache_key") }.distinct()

    @Test fun realOwnerAndExecutorReadPairsWithReceiverCallWithoutInboxInjectionOrInfoDrivenParentRequest() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage()
        val childRequests = Collections.synchronizedList(mutableListOf<ProviderRequest>())
        val parentRequests = mutableListOf<ProviderRequest>()
        val bTool = CountDownLatch(1); val bRelease = CountDownLatch(1)
        val aRequests = AtomicInteger(); val bRequests = AtomicInteger(); val ordinaryCalls = AtomicInteger()
        val callbackFailure = AtomicReference<Throwable?>()
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val child = provider(callbackFailure) { request ->
            remember(childRequests, request)
            val text = request.messages.toString()
            if (text.contains("TASK_A")) when (aRequests.incrementAndGet()) {
                1 -> tools(call("a-list", "agent_mailbox", JSONObject().put("action", "list")))
                2 -> {
                    val peers = JSONObject(toolResult(request, "a-list").getString("content")).getJSONArray("members")
                    val b = (0 until peers.length()).map(peers::getJSONObject).single { it.getString("tool_call_id") == "launch-b" }
                    assertTrue(b.getBoolean("can_receive"))
                    tools(call("a-send", "agent_mailbox", JSONObject().put("action", "send").put("receiver", b.getString("task_id"))
                        .put("message_id", "facts").put("content", "PRIVATE_INFO_BODY")))
                }
                3 -> {
                    val receipt = JSONObject(toolResult(request, "a-send").getString("content"))
                    assertEquals("STORED", receipt.getJSONObject("receipt").getString("status"))
                    answer("A finished without copying INFO into report")
                }
                else -> error("unexpected A request")
            } else when (bRequests.incrementAndGet()) {
                1 -> {
                    assertTrue(text.contains("TASK_B")); assertFalse(text.contains("PRIVATE_INFO_BODY"))
                    tools(call("b-ongoing", "read_file", JSONObject().put("path", "/fixture")), signed = true)
                }
                2 -> {
                    assertEquals("ONGOING_TOOL_RESULT", toolResult(request, "b-ongoing").getString("content"))
                    assertFalse(text.contains("PRIVATE_INFO_BODY"))
                    assertTrue(text.contains("opaque-signature"))
                    tools(call("b-list", "agent_mailbox", JSONObject().put("action", "list").put("direction", "inbox")))
                }
                3 -> {
                    assertFalse(text.contains("PRIVATE_INFO_BODY"))
                    val inbox = JSONObject(toolResult(request, "b-list").getString("content"))
                    assertEquals("facts", inbox.getJSONArray("messages").getJSONObject(0).getString("message_id"))
                    assertEquals("STORED", inbox.getJSONArray("messages").getJSONObject(0).getString("status"))
                    tools(call("b-real-read", "agent_mailbox", JSONObject().put("action", "read").put("message_id", "facts")), signed = true)
                }
                4 -> {
                    val paired = toolResult(request, "b-real-read")
                    val value = JSONObject(paired.getString("content"))
                    assertEquals("PRIVATE_INFO_BODY", value.getString("content"))
                    assertEquals("READ", value.getJSONObject("receipt").getString("status"))
                    val holders = (0 until request.messages.length()).map(request.messages::getJSONObject)
                        .filter { it.toString().contains("PRIVATE_INFO_BODY") }
                    assertEquals(1, holders.size); assertEquals("tool", holders.single().getString("role"))
                    assertEquals("b-real-read", holders.single().getString("tool_call_id"))
                    answer("B finished without copying INFO into report")
                }
                else -> error("unexpected B request")
            }
        }
        val worker = executor(parent, store, child, AgentModelClient.ToolExecutor {
            assertEquals("b-ongoing", it.id); ordinaryCalls.incrementAndGet(); bTool.countDown()
            check(bRelease.await(5, TimeUnit.SECONDS)); AgentModelClient.ToolResult("ONGOING_TOOL_RESULT")
        })
        val owner = YieldableSubagents(parent, store, parentRunId = "parent",
            executeGrouped = { invocation, binding, started -> worker.execute(invocation.round, invocation, binding, onStarted = started)
                .also { outcomes[invocation.id] = JSONObject(it.content) } }) { invocation, started ->
            worker.execute(invocation.round, invocation, onStarted = started)
        }
        var round = 0
        val primary = provider(callbackFailure) { request ->
            remember(parentRequests, request)
            assertFalse(request.messages.toString().contains("PRIVATE_INFO_BODY"))
            when (++round) {
                1 -> tools(call("launch-b", "subagent", args("TASK_B")))
                2 -> { assertTrue(bTool.await(5, TimeUnit.SECONDS)); tools(call("launch-a", "subagent", args("TASK_A"))) }
                3 -> {
                    assertEquals("facts", store.stored.poll(5, TimeUnit.SECONDS))
                    assertNotNull(store.finished.poll(5, TimeUnit.SECONDS))
                    assertEquals(1, bRequests.get())
                    assertTrue(owner.peekReports().isEmpty())
                    bRelease.countDown(); answer("independent work finished")
                }
                4 -> { assertTrue(request.messages.toString().contains("full_report_ref:")); answer("joint consumed") }
                else -> error("INFO caused an unsolicited parent model request")
            }
        }
        try {
            val extra = JSONArray().also { AgentSubagentToolCatalog.appendSchemas(it, SubagentProfile.BUILTIN_PROFILES, true) }
            val response = AgentModelClient.complete(config, "parent task", AgentModelClient.ToolExecutor { error("unexpected parent tool") },
                provider = primary, runController = parent, additionalTools = extra, yieldableSubagents = owner,
                sessionId = "parent-session", operationId = "parent")
            callbackFailure.get()?.let { throw AssertionError("Provider callback assertion failed", it) }
            assertEquals("joint consumed", response.content)
            assertEquals(4, parentRequests.size); assertEquals(3, aRequests.get()); assertEquals(4, bRequests.get())
            assertEquals(1, ordinaryCalls.get()); assertEquals(1, store.messages.length())
            assertEquals("READ", store.messages.getJSONObject(0).getString("status"))
            assertEquals(1, response.transcript.count { it.messageId.startsWith("subagent-report-") })
            assertEquals(listOf("launch-b", "launch-a"), response.transcript.filter { it.role == "tool" }.map { it.toolCallId })
            assertTrue(parentRequests.all { request -> !request.tools.toString().contains("\"name\":\"agent_mailbox\"") })
            assertEquals(1, keys(parentRequests).size)
            val b = childRequests.filter { it.messages.toString().contains("TASK_B") }
            assertEquals(4, b.size)
            assertTrue(b.take(3).none { it.messages.toString().contains("PRIVATE_INFO_BODY") })
            val holders = (0 until b.last().messages.length()).map(b.last().messages::getJSONObject)
                .filter { it.toString().contains("PRIVATE_INFO_BODY") }
            assertEquals(1, holders.size)
            assertEquals("tool", holders.single().getString("role"))
            assertEquals("b-real-read", holders.single().getString("tool_call_id"))
            assertEquals("PRIVATE_INFO_BODY", JSONObject(toolResult(b.last(), "b-real-read").getString("content")).getString("content"))
            val declaration = (0 until b[2].messages.length()).map(b[2].messages::getJSONObject)
            assertFalse(declaration.any { it.optString("role") == "tool" && it.optString("tool_call_id") == "b-real-read" })
            val readCalls = (0 until b.last().messages.length()).map(b.last().messages::getJSONObject)
                .flatMap { message -> AgentConversationCodec.parseToolCalls(message) }.filter { it.id == "b-real-read" }
            assertEquals(1, readCalls.size); assertEquals("agent_mailbox", readCalls.single().name)
            assertEquals("read", JSONObject(readCalls.single().argumentsJson).getString("action"))
            assertEquals(setOf("launch-a", "launch-b"), outcomes.keys)
            for (outcome in outcomes.values) {
                assertTrue(outcome.getBoolean("ok")); assertEquals("completed", outcome.getString("status"))
                // Natural completion is engine success, not semantic task-status inference.
                assertEquals("unknown", outcome.getString("task_status"))
            }
            assertEquals(1, b.map { it.sessionId }.distinct().size); assertEquals(1, keys(b).size)
            assertEquals(1, b.map { it.tools.toString() }.distinct().size)
            for ((before, after) in b.zipWithNext()) for (index in 0 until before.messages.length())
                assertEquals(before.messages.get(index).toString(), after.messages.get(index).toString())
        } finally { bRelease.countDown(); parent.cancel(); owner.close() }
    }

    @Test fun ungroupedAndExplicitlyDeniedCallsStayUnavailableAndDoNotReachOrdinaryTools() {
        for (denied in listOf(false, true)) {
            val parent = AgentRunController(); val store = MailboxMemoryStorage(); val requests = mutableListOf<ProviderRequest>()
            var round = 0
            val model = provider { request ->
                remember(requests, request)
                assertFalse(request.tools.toString().contains("\"name\":\"agent_mailbox\""))
                if (++round == 1) tools(call("forged-mailbox", "agent_mailbox", JSONObject().put("action", "list")))
                else { assertEquals("TOOL_NOT_AVAILABLE", JSONObject(toolResult(request, "forged-mailbox").getString("content")).getString("code")); answer() }
            }
            val worker = executor(parent, store, model)
            val invocation = if (denied) {
                store.reserveGroupMember("task", "launch", 1, "fixture", "batch")
                SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "task")
            } else null
            val input = args("fixture", false).also { if (denied) it.put("tools", JSONArray(listOf("read_file"))) }
            try {
                val result = worker.execute(1, AgentModelClient.ToolCall("launch", "subagent", input.toString()), invocation) { id, _ ->
                    if (invocation != null) store.started("task", "launch", 1, id, "fixture", "accepted")
                }
                assertTrue(JSONObject(result.content).getBoolean("ok")); assertEquals(2, requests.size)
                assertEquals(0, store.messages.length())
                if (denied) assertFalse(store.members.getValue("task").allowed)
            } finally { parent.cancel() }
        }
    }

    @Test fun planModeGateBlocksMailboxInsteadOfBypassingChildWriteRestrictions() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val gate = PlanRunGate("fixture", "parent", parent)
        val invocation = SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "task")
        store.reserveGroupMember("task", "launch", 1, "fixture", "batch")
        var round = 0
        val model = provider { request ->
            if (++round == 1) tools(call("mailbox-plan", "agent_mailbox", JSONObject().put("action", "list")))
            else {
                assertEquals("PLAN_CHILD_BLOCKED", JSONObject(toolResult(request, "mailbox-plan").getString("content")).getString("code"))
                answer()
            }
        }
        try {
            val result = executor(parent, store, model, gate = gate).execute(1,
                AgentModelClient.ToolCall("launch", "subagent", args("fixture", false).toString()), invocation) { id, _ ->
                store.started("task", "launch", 1, id, "fixture", "accepted")
            }
            assertTrue(JSONObject(result.content).getBoolean("ok")); assertEquals(2, round)
            assertEquals(0, store.messages.length())
        } finally { gate.end(); parent.cancel() }
    }

    @Test fun parentRevisionRejectsStaleMailboxCallAndDoesNotCreateInfoOrReplanFromInfo() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val registry = SubagentControlRegistry()
        store.add("b")
        val invocation = SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "task")
        store.reserveGroupMember("task", "launch", 1, "fixture", "batch")
        var subagentId = ""; var round = 0
        val model = provider { request -> when (++round) {
            1 -> {
                val revised = registry.execute(AgentModelClient.ToolCall("revision", "subagent_control", JSONObject()
                    .put("action", "revise").put("subagent_id", subagentId).put("instruction", "NEW_PARENT_DIRECTION").toString()))
                assertTrue(JSONObject(revised.content).getBoolean("ok"))
                tools(call("stale-send", "agent_mailbox", JSONObject().put("action", "send").put("receiver", "b")
                    .put("message_id", "stale").put("content", "STALE_INFO")))
            }
            2 -> {
                assertEquals("SUBAGENT_REPLAN_REQUIRED", JSONObject(toolResult(request, "stale-send").getString("content")).getString("code"))
                assertTrue(request.messages.toString().contains("NEW_PARENT_DIRECTION")); answer()
            }
            else -> error("extra request")
        } }
        try {
            val result = executor(parent, store, model, registry = registry).execute(1,
                AgentModelClient.ToolCall("launch", "subagent", args("fixture", false).toString()), invocation) { id, _ ->
                subagentId = id; store.started("task", "launch", 1, id, "fixture", "accepted")
            }
            assertTrue(JSONObject(result.content).getBoolean("ok")); assertEquals(0, store.messages.length())
            assertEquals(2, round)
        } finally { parent.cancel() }
    }

    @Test fun mailboxInvocationRemainsBoundAcrossRealChildCompactionWithoutInjectingUnreadContent() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val a = store.add("a")
        val conversation = "mailbox-compaction-${UUID.randomUUID()}"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER.copy(explicitTools = setOf("agent_mailbox", "read_file")))
        SubagentSessionStore.complete(conversation, entry.subagentId, listOf(
            AgentModelClient.ConversationMessage("user", "OLD_USER_CONTEXT".repeat(3000)),
            AgentModelClient.ConversationMessage("assistant", "OLD_ASSISTANT_CONTEXT".repeat(3000))))
        store.reserveGroupMember("b", "launch", 1, "fixture", "batch")
        val invocation = SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "b")
        val chats = AtomicInteger(); val compactions = AtomicInteger()
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertFalse(request.messages.toString().contains("UNREAD_COMPACTION_INFO"))
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    assertEquals(1_000_000, request.config.contextWindow)
                    compactions.incrementAndGet(); return answer("retained old context")
                }
                assertEquals(128_000, request.config.contextWindow)
                return when (chats.incrementAndGet()) {
                    1 -> {
                        val sent = SubagentMailbox(a, store) {}.execute(AgentModelClient.ToolCall("send-before-compaction", "agent_mailbox",
                            JSONObject().put("action", "send").put("receiver", "b").put("message_id", "unread")
                                .put("content", "UNREAD_COMPACTION_INFO").toString()))
                        assertTrue(JSONObject(sent.content).getBoolean("ok"))
                        onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120_000)))
                        tools(call("do-once", "read_file", JSONObject().put("path", "/fixture")))
                    }
                    2 -> tools(call("list-after-compaction", "agent_mailbox", JSONObject().put("action", "list")))
                    3 -> {
                        val listed = JSONObject(toolResult(request, "list-after-compaction").getString("content"))
                        assertEquals("b", listed.getJSONObject("self").getString("task_id"))
                        assertEquals("STORED", listed.getJSONArray("messages").getJSONObject(0).getString("status")); answer()
                    }
                    else -> error("unexpected child request")
                }
            }
        }
        val ordinary = AtomicInteger()
        try {
            val result = executor(parent, store, model, AgentModelClient.ToolExecutor {
                ordinary.incrementAndGet(); AgentModelClient.ToolResult("performed once")
            }, conversation = conversation, childConfig = config.copy(contextWindow = 128_000, autoCompactionEnabled = true),
                compactionTarget = { child -> AgentCompactionTarget(child.copy(contextWindow = 1_000_000), model) }).execute(1,
                AgentModelClient.ToolCall("launch", "subagent", JSONObject().put("prompt", "CURRENT_TASK")
                    .put("description", "fixture").put("resume", entry.subagentId).toString()), invocation) { id, _ ->
                store.started("b", "launch", 1, id, "fixture", "accepted")
            }
            assertTrue(JSONObject(result.content).getBoolean("ok")); assertEquals(3, chats.get())
            assertTrue(compactions.get() >= 1); assertEquals(1, ordinary.get())
            assertEquals("STORED", store.messages.getJSONObject(0).getString("status"))
            assertTrue(entry.compactionCount.get() >= 1)
        } finally { parent.cancel() }
    }

    @Test fun explicitResumeKeepsSessionHistoryWithOnlyCurrentInvocationMembershipAndLegacyResumeStillWorks() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val requests = mutableListOf<ProviderRequest>()
        val model = provider { request -> remember(requests, request); answer() }
        val worker = executor(parent, store, model)
        fun invoke(task: String, group: String, resume: String? = null): JSONObject {
            store.reserveGroupMember(task, "call-$task", 1, "fixture", group)
            val input = args("TASK", false).also { if (resume != null) { it.remove("subagent_type"); it.put("resume", resume) } }
            return JSONObject(worker.execute(1, AgentModelClient.ToolCall("call-$task", "subagent", input.toString()),
                SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", group), task)) { id, _ ->
                store.started(task, "call-$task", 1, id, "fixture", "accepted")
            }.content)
        }
        try {
            val first = invoke("one", "batch"); val id = first.getString("subagent_id")
            assertTrue(invoke("two", "batch", id).getBoolean("ok"))
            assertEquals(id, store.members.getValue("two").subagentId)
            assertTrue(invoke("other", "other-group", id).getBoolean("ok"))
            val ungroupedResume = JSONObject().put("prompt", "new task").put("description", "fixture").put("resume", id)
            assertTrue(JSONObject(worker.execute(1, AgentModelClient.ToolCall("ungrouped", "subagent", ungroupedResume.toString()))
                .content).getBoolean("ok"))
            assertEquals(4, requests.size); assertEquals(1, requests.map { it.sessionId }.distinct().size)
            assertEquals(requests[0].tools.toString(), requests[1].tools.toString()); assertEquals(1, keys(requests).size)
            assertFalse(requests.last().tools.toString().contains("\"name\":\"agent_mailbox\""))
        } finally { parent.cancel() }
    }

    @Test fun targetedCancelClosesActualReceiverAndNeverRestartsItForInfo() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val registry = SubagentControlRegistry()
        val a = store.add("a"); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor(); val requests = AtomicInteger()
        store.reserveGroupMember("b", "launch", 1, "fixture", "batch")
        val invocation = SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "b")
        val model = provider { entered.countDown(); requests.incrementAndGet(); check(release.await(5, TimeUnit.SECONDS)); answer() }
        var id = ""
        val worker = executor(parent, store, model, registry = registry)
        fun send(message: String) = JSONObject(SubagentMailbox(a, store) {}.execute(AgentModelClient.ToolCall("send", "agent_mailbox",
            JSONObject().put("action", "send").put("receiver", "b").put("message_id", message).put("content", "INFO").toString())).content)
        try {
            val running = pool.submit(Callable { worker.execute(1, AgentModelClient.ToolCall("launch", "subagent", args("fixture", false).toString()),
                invocation) { childId, _ -> id = childId; store.started("b", "launch", 1, childId, "fixture", "accepted") } })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(send("before-stop").getBoolean("ok"))
            val cancelled = registry.execute(AgentModelClient.ToolCall("cancel", "subagent_control", JSONObject()
                .put("action", "cancel").put("subagent_id", id).toString()))
            assertTrue(JSONObject(cancelled.content).getBoolean("ok"))
            assertEquals("MAILBOX_RECIPIENT_TERMINAL", send("after-stop").getString("code"))
            assertTrue(send("before-stop").getBoolean("duplicate"))
            assertEquals("STORED", store.messages.getJSONObject(0).getString("status"))
            release.countDown()
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { running.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is AgentRunCancelledException)
            assertEquals(1, requests.get()); assertEquals(1, store.messages.length())
        } finally { release.countDown(); parent.cancel(); pool.shutdownNow() }
    }

    @Test fun actualResumeCannotReadAnOldInvocationInboxEvenWithSameSubagentSession() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val a = store.add("a")
        var round = 0
        val model = provider { request -> when (++round) {
            1 -> answer()
            2 -> tools(call("old-inbox", "agent_mailbox", JSONObject().put("action", "read").put("message_id", "old")))
            3 -> {
                assertEquals("MAILBOX_MESSAGE_UNAVAILABLE", JSONObject(toolResult(request, "old-inbox").getString("content")).getString("code"))
                assertFalse(request.messages.toString().contains("OLD_UNREAD_BODY")); answer()
            }
            else -> error("unexpected request")
        } }
        val worker = executor(parent, store, model)
        fun invoke(task: String, resume: String? = null): JSONObject {
            store.reserveGroupMember(task, "call-$task", 1, "fixture", "batch")
            val input = args("TASK", false).also { if (resume != null) { it.remove("subagent_type"); it.put("resume", resume) } }
            return JSONObject(worker.execute(1, AgentModelClient.ToolCall("call-$task", "subagent", input.toString()),
                SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), task)) { id, _ ->
                store.started(task, "call-$task", 1, id, "fixture", "accepted")
                if (resume == null) {
                    val sent = SubagentMailbox(a, store) {}.execute(AgentModelClient.ToolCall("send", "agent_mailbox", JSONObject()
                        .put("action", "send").put("receiver", task).put("message_id", "old").put("content", "OLD_UNREAD_BODY").toString()))
                    assertTrue(JSONObject(sent.content).getBoolean("ok"))
                }
            }.content)
        }
        try {
            val original = invoke("original")
            assertTrue(invoke("resumed", original.getString("subagent_id")).getBoolean("ok"))
            assertEquals("STORED", store.messages.getJSONObject(0).getString("status"))
            assertEquals(3, round)
        } finally { parent.cancel() }
    }

    private fun checkCloseFailure(failedProvider: Boolean, cancelDuringClose: Boolean) {
        val parent = AgentRunController(); val memory = MailboxMemoryStorage(); val registry = SubagentControlRegistry()
        val conversation = "mailbox-close-${UUID.randomUUID()}"
        val closing = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val storage = object : SubagentMailbox.Storage by memory {
            override fun close(identity: SubagentMailbox.Identity) {
                memory.close(identity); closes.incrementAndGet(); closing.countDown()
                if (cancelDuringClose) check(release.await(5, TimeUnit.SECONDS))
                error("fixture close persistence failure")
            }
        }
        memory.reserveGroupMember("task", "launch", 1, "fixture", "batch")
        val invocation = SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), "task")
        var round = 0; var id = ""
        val callbackFailure = AtomicReference<Throwable?>()
        val model = provider(callbackFailure) { _ ->
            if (!failedProvider) answer("ACTUAL_COMPLETED_ARTIFACT")
            else if (++round == 1) tools(call("performed", "read_file", JSONObject().put("path", "/fixture")))
            else throw AgentModelFailure("FIXTURE_PERMANENT", false, "ACTUAL_PROVIDER_FAILURE")
        }
        val worker = executor(parent, storage, model, AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ACTUAL_TOOL_ARTIFACT") },
            conversation = conversation, registry = registry)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit(Callable { worker.execute(1,
                AgentModelClient.ToolCall("launch", "subagent", args("fixture", false).toString()), invocation) { childId, _ ->
                id = childId; memory.started("task", "launch", 1, childId, "fixture", "accepted")
            } })
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            if (cancelDuringClose) {
                val cancelled = registry.execute(AgentModelClient.ToolCall("cancel-before-finalization", "subagent_control",
                    JSONObject().put("action", "cancel").put("subagent_id", id).toString()))
                assertTrue(JSONObject(cancelled.content).getBoolean("ok"))
                release.countDown()
                val thrown = assertThrows(java.util.concurrent.ExecutionException::class.java) { running.get(5, TimeUnit.SECONDS) }
                assertTrue(thrown.cause is AgentRunCancelledException)
            } else {
                val outcome = JSONObject(running.get(5, TimeUnit.SECONDS).content)
                assertEquals(!failedProvider, outcome.getBoolean("ok"))
                assertEquals(if (failedProvider) "failed" else "completed", outcome.getString("status"))
                assertEquals("MAILBOX_CLOSE_FAILED", outcome.getJSONObject("cleanup_failure").getString("code"))
                assertTrue(outcome.getJSONObject("cleanup_failure").getBoolean("outcome_preserved"))
                assertFalse(outcome.toString().contains("SUBAGENT_START_FAILED"))
                if (failedProvider) {
                    assertEquals("SUBAGENT_FAILED", outcome.getString("code"))
                    assertTrue(outcome.getString("message").contains("ACTUAL_PROVIDER_FAILURE"))
                    assertTrue(callbackFailure.get() is AgentModelFailure)
                } else {
                    callbackFailure.get()?.let { throw AssertionError(it) }
                    assertEquals("ACTUAL_COMPLETED_ARTIFACT", outcome.getString("summary"))
                    assertEquals("unknown", outcome.getString("task_status"))
                }
                // The real registry has already selected a terminal result; later cancel cannot replace it.
                val late = registry.execute(AgentModelClient.ToolCall("late-cancel", "subagent_control", JSONObject()
                    .put("action", "cancel").put("subagent_id", id).toString()))
                assertEquals("SUBAGENT_NOT_RUNNING", JSONObject(late.content).getString("code"))
            }
            assertEquals(1, closes.get())
            val entry = requireNotNull(SubagentSessionStore.get(conversation, id))
            assertFalse(entry.running.get())
            val expected = if (failedProvider) "ACTUAL_TOOL_ARTIFACT" else "ACTUAL_COMPLETED_ARTIFACT"
            assertTrue(entry.history.any { it.content.contains(expected) })
        } finally { release.countDown(); parent.cancel(); pool.shutdownNow() }
    }

    @Test fun mailboxCloseFailurePreservesActualSuccessAndHistoryAfterRegistryFinalization() =
        checkCloseFailure(failedProvider = false, cancelDuringClose = false)

    @Test fun mailboxCloseFailurePreservesActualFailureAndToolHistoryAfterRegistryFinalization() =
        checkCloseFailure(failedProvider = true, cancelDuringClose = false)

    @Test fun cancelDuringFailedMailboxCloseStillWinsBeforeActualRegistryFinalization() {
        for (failed in listOf(false, true)) checkCloseFailure(failedProvider = failed, cancelDuringClose = true)
    }
}
