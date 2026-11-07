package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskGroupLoopTest {
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
        apiKey = "fixture", model = "gpt-fixture", systemPrompt = "stable-system")
    private open class Storage : YieldableSubagents.Persistence {
        val finished = java.util.concurrent.LinkedBlockingQueue<String>()
        val sealed = CountDownLatch(1)
        override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) = Unit
        override fun finished(taskId: String, report: String): Boolean { finished.offer(taskId); return true }
        override fun sealGroups(keys: Collection<String>) { if (keys.isNotEmpty()) sealed.countDown() }
        override fun interrupted() = Unit
    }
    private fun call(id: String, name: String = "subagent", args: JSONObject = JSONObject()
        .put("prompt", id).put("description", id).put("group_key", "batch")) = JSONObject().put("id", id)
        .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tools(vararg calls: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls.toList())))
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun result() = AgentModelClient.ToolResult(SubagentTaskResult.fromFinal(
        "task_status: completed\nsummary: member-summary\nevidence: fixture:1\nremaining: -\nnext_action: -")
        .attach(JSONObject().put("ok", true).put("status", "completed").put("raw", "full-member-not-in-joint")).toString())
    private fun schemas() = JSONArray().also {
        AgentSubagentToolCatalog.appendSchemas(it, SubagentProfile.BUILTIN_PROFILES, true)
        it.put(AgentToolSchema.function("fixture_work", "independent", JSONObject().put("type", "object")))
    }
    private fun provider(script: (ProviderRequest, Int) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        var round = 0
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = script(request, ++round)
    }

    @Test fun crossRoundACompletesBeforeLateBParentContinuesAndOnlyNaturalWaitSeals() {
        val controller = AgentRunController(); val storage = Storage(); val bRelease = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<ProviderRequest>())
        val independent = AtomicInteger(); val launches = AtomicInteger(); val natural = CountDownLatch(1)
        val owner = YieldableSubagents(controller, storage, parentRunId = "parent") { call, start ->
            launches.incrementAndGet(); start("same-child-${call.id}", "")
            if (call.id == "b") check(bRelease.await(5, TimeUnit.SECONDS))
            result()
        }
        val model = provider { request, round ->
            requests += request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
            when (round) {
                1 -> tools(call("a"))
                2 -> {
                    assertNotNull(storage.finished.poll(3, TimeUnit.SECONDS))
                    assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch"))
                    assertTrue(owner.peekReports().isEmpty())
                    assertEquals(1L, storage.sealed.count)
                    tools(call("own", "fixture_work", JSONObject()))
                }
                3 -> {
                    assertEquals(1, independent.get())
                    assertFalse(request.messages.toString().contains("member-summary"))
                    assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch"))
                    tools(call("b"))
                }
                4 -> {
                    assertEquals(2, launches.get()); assertEquals(1L, storage.sealed.count)
                    answer("independent work done")
                }
                5 -> {
                    assertTrue(request.messages.toString().contains("member-summary"))
                    assertFalse(request.messages.toString().contains("full-member-not-in-joint"))
                    assertEquals(SubagentTaskGroup.State.DELIVERED, owner.groupState("batch"))
                    answer("all done")
                }
                else -> error("extra parent model request")
            }
        }
        val response = AtomicReference<AgentModelClient.ModelResponse.Text?>(); val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try {
                response.set(AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor {
                    assertEquals("own", it.id); independent.incrementAndGet(); AgentModelClient.ToolResult("own-result")
                }, provider = model, runController = controller, yieldableSubagents = owner, additionalTools = schemas(),
                    sessionId = "stable-session", operationId = "parent", history = listOf(AgentModelClient.ConversationMessage("user", "old-history")),
                    onTranscript = { if (it.any { m -> m.content == "independent work done" }) natural.countDown() }))
            } catch (t: Throwable) { failure.set(t) }
        }
        try {
            runner.start(); assertTrue(natural.await(4, TimeUnit.SECONDS)); assertTrue(storage.sealed.await(3, TimeUnit.SECONDS))
            assertEquals(4, requests.size)
            Thread.sleep(80); assertEquals("waiting must not poll the model", 4, requests.size)
            bRelease.countDown(); runner.join(5000); assertFalse(runner.isAlive); failure.get()?.let { throw AssertionError(it) }
            assertEquals("all done", response.get()!!.content)
            assertEquals(5, requests.size)
            val joint = response.get()!!.transcript.filter { it.messageId.startsWith("subagent-report-") }.single()
            assertEquals(SubagentTaskGroup.messageId("parent", "batch"), joint.messageId)
            assertEquals(2, Regex("full_report_ref:").findAll(joint.content).count())
            val before = requests[3].messages; val after = requests[4].messages
            for (i in 0 until before.length()) assertEquals(before.get(i).toString(), after.get(i).toString())
            assertEquals(1, requests.map { it.tools.toString() }.distinct().size)
            assertEquals(listOf("stable-session"), requests.map { it.sessionId }.distinct())
            assertEquals(1, requests.map { request -> JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key") }.distinct().size)
        } finally { bRelease.countDown(); controller.cancel(); owner.close(); runner.join(5000) }
    }

    @Test fun explicitSealToolIsPairedAndLateMemberDoesNotExecute() {
        val controller = AgentRunController(); val storage = Storage(); val launches = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> launches.incrementAndGet(); start("child", ""); result() }
        val requests = mutableListOf<ProviderRequest>()
        val model = provider { request, round ->
            requests += request.copy(messages = JSONArray(request.messages.toString()))
            when (round) {
                1 -> tools(call("a"), call("seal", "subagent_seal_group", JSONObject().put("group_key", "batch")), call("late"))
                2 -> {
                    val history = request.messages.toString()
                    assertTrue(history.contains("SUBAGENT_GROUP_SEALED"))
                    assertTrue(history.contains("sealed"))
                    answer("finished own work")
                }
                3 -> answer("joint received")
                else -> error("extra request")
            }
        }
        try {
            val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("seal must route to owner") },
                provider = model, runController = controller, yieldableSubagents = owner, additionalTools = schemas())
            assertEquals(1, launches.get())
            assertEquals(listOf("a", "seal", "late"), response.transcript.filter { it.role == "tool" }.map { it.toolCallId })
            assertEquals(1, response.transcript.count { it.messageId.startsWith("subagent-report-") })
            assertTrue(requests.size in 2..3)
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun completedOpenGroupCannotLetNaturalAnswerFinishWithoutJointReport() {
        val controller = AgentRunController(); val storage = Storage(); val seen = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> start("child", ""); result() }
        val model = provider { request, round ->
            seen.incrementAndGet()
            when (round) {
                1 -> tools(call("a"))
                2 -> { assertNotNull(storage.finished.poll(3, TimeUnit.SECONDS)); answer("natural end") }
                3 -> { assertTrue(request.messages.toString().contains("full_report_ref:")); answer("joint consumed") }
                else -> error("unexpected polling")
            }
        }
        try {
            val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("no ordinary tool") },
                provider = model, runController = controller, yieldableSubagents = owner, additionalTools = schemas())
            assertEquals("joint consumed", response.content); assertEquals(3, seen.get())
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun newInputAtNaturalEndAllowsFurtherMembershipBeforeAutoSeal() {
        val controller = AgentRunController(); val storage = Storage(); val launches = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> launches.incrementAndGet(); start("child", ""); result() }
        val model = provider { request, round -> when (round) {
            1 -> tools(call("a"))
            2 -> { assertTrue(controller.steer("also inspect B")); answer("tentative end") }
            3 -> {
                assertTrue(request.messages.toString().contains("also inspect B"))
                assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch")); tools(call("b"))
            }
            4 -> answer("now wait")
            5 -> answer("done")
            else -> error("extra request")
        } }
        try {
            val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("no ordinary tool") },
                provider = model, runController = controller, yieldableSubagents = owner, additionalTools = schemas())
            assertEquals(2, launches.get()); assertEquals(1, response.transcript.count { it.messageId.startsWith("subagent-report-") })
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun jointReportMustReachChatIntactBeforeCompactionAndDoesNotInvokeSummaryModelForGrouping() {
        val controller = AgentRunController(); val storage = Storage(); val seen = java.util.concurrent.atomic.AtomicBoolean()
        val chats = AtomicInteger(); val compactions = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> start("child", ""); result() }
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    assertEquals(1_000_000, request.config.contextWindow)
                    if (request.messages.toString().contains("full_report_ref:")) assertTrue("fresh projection cannot be compacted before chat", seen.get())
                    compactions.incrementAndGet(); return answer("retained prior context")
                }
                assertEquals(128_000, request.config.contextWindow)
                onEvent(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 120_000)))
                return when (chats.incrementAndGet()) {
                    1 -> tools(call("a"))
                    2 -> { assertNotNull(storage.finished.poll(3, TimeUnit.SECONDS)); answer("own work done") }
                    3 -> {
                        assertTrue(request.messages.toString().contains("full_report_ref:"))
                        assertTrue(request.messages.toString().contains("member-summary")); seen.set(true)
                        tools(call("own", "fixture_work", JSONObject()))
                    }
                    4 -> answer("done")
                    else -> error("grouping must not call an extra summary model")
                }
            }
        }
        try {
            AgentModelClient.complete(config.copy(contextWindow = 128_000, autoCompactionEnabled = true), "goal",
                AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("work") }, provider = model,
                compactionTarget = { AgentCompactionTarget(config.copy(contextWindow = 1_000_000), model) },
                runController = controller, yieldableSubagents = owner, additionalTools = schemas(),
                history = listOf(AgentModelClient.ConversationMessage("user", "old".repeat(20_000)),
                    AgentModelClient.ConversationMessage("assistant", "old-answer".repeat(10_000))))
            assertTrue(seen.get()); assertEquals(4, chats.get()); assertTrue(compactions.get() > 0)
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun naturalWaitBoundaryDoesNotSealWhenNewInputAlreadyQueued() {
        val controller = AgentRunController(); val sealed = AtomicInteger()
        assertTrue(controller.steer("new work"))
        assertFalse(controller.atNaturalWait { sealed.incrementAndGet() })
        assertEquals(0, sealed.get()); controller.pollSteeringInput()
        assertTrue(controller.atNaturalWait { sealed.incrementAndGet() }); assertEquals(1, sealed.get())
        assertTrue("group barrier must not close incoming user input", controller.steer("later guidance"))
    }

    @Test fun truncatedParentTurnCannotAutoSealAnOpenGroupAsNaturalFinish() {
        val controller = AgentRunController(); val storage = Storage()
        val owner = YieldableSubagents(controller, storage) { _, start -> start("child", ""); result() }
        val model = provider { _, round -> when (round) {
            1 -> tools(call("a"))
            2 -> ProviderResponse(JSONObject().put("role", "assistant").put("content", "truncated independent work").put("finish_reason", "length"))
            else -> error("no model polling or automatic replay")
        } }
        try {
            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("no ordinary tool") },
                    provider = model, runController = controller, yieldableSubagents = owner, additionalTools = schemas())
            }
            assertEquals("SUBAGENT_GROUP_INCOMPLETE_PARENT_TURN", (failure.cause as AgentModelFailure).code)
            assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch")); assertEquals(1L, storage.sealed.count)
        } finally { controller.cancel(); owner.close() }
    }
}
