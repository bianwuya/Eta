package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRecentFixesIntegrationTest {
    private lateinit var directory: java.io.File
    private lateinit var controller: AgentRunController
    private lateinit var gate: PlanRunGate
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
        apiKey = "fixture", model = "gpt-integration-fixture", systemPrompt = "stable-system")
    private val childArguments = JSONObject().put("prompt", "read-only inspection").put("description", "inspect")
    private val writeArguments = JSONObject().put("command", "fixture-independent-write")

    @Before fun setup() {
        directory = Files.createTempDirectory("plan-recent-fixes-").toFile()
        PlanModeStore.resetForTest()
        PlanModeStore.initializeAt(directory)
        controller = AgentRunController()
        gate = PlanRunGate("integration-chat", "integration-run", controller)
        PlanModeStore.listen("integration-run", controller::signalWorkAvailable) {
            !controller.isCancelled && !controller.hasPendingSteering
        }
    }

    @After fun cleanup() {
        controller.cancel()
        PlanModeStore.resetForTest()
        directory.deleteRecursively()
    }

    private fun call(id: String, name: String, args: JSONObject = JSONObject()) = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun begin(step: String) = call("begin-$step", PlanRunGate.BEGIN,
        JSONObject().put("step_id", step).put("revision", 1))
    private fun tools(vararg calls: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls.toList())))
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun schema(name: String) = AgentToolSchema.function(name, "fixture", JSONObject().put("type", "object"))
    private fun schemas(groupsSupported: Boolean = false) = JSONArray().put(AgentSubagentToolCatalog.schema(groupsSupported = groupsSupported)).put(schema("terminal"))
        .put(schema("ask_user_questions")).also(PlanRunGate::schemas)
    private fun provider(script: (ProviderRequest, Int) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "integration-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        private val rounds = AtomicInteger()
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            script(request, rounds.incrementAndGet())
    }
    private fun action(name: String, args: JSONObject) = PlanAction(name, PlanRunGate.canonical(args))
    private fun completedChild(marker: String): AgentModelClient.ToolResult {
        val semantic = io.github.mangi.eta.local.subagentcontext.SubagentTaskResult.fromFinal(
            "task_status: completed\nsummary: $marker\nevidence: fixture:1\nremaining: -\nnext_action: -")
        return AgentModelClient.ToolResult(semantic.attach(JSONObject().put("ok", true)
            .put("status", "completed").put("summary", marker)).toString())
    }
    private fun approve(vararg steps: PlanStep) {
        val plan = PlanModeStore.propose("integration-chat", "integration-run", "Integration", "Exact approved actions", steps.toList())
        assertTrue(PlanModeStore.approve(plan.planId, plan.revision))
    }
    private class Persistence : YieldableSubagents.Persistence {
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        val interrupted = AtomicInteger()
        override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) {
            starts.incrementAndGet()
        }
        override fun finished(taskId: String, report: String): Boolean { finishes.incrementAndGet(); return true }
        override fun interrupted() { interrupted.incrementAndGet() }
    }
    private fun complete(provider: AgentProviderClient, owner: YieldableSubagents, executor: AgentModelClient.ToolExecutor,
        onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {}) = AgentModelClient.complete(
        config, "integration goal", executor, provider = provider, runController = controller, planGate = gate,
        history = listOf(AgentModelClient.ConversationMessage("user", "stable-history")), sessionId = "stable-session",
        additionalTools = schemas(), capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        yieldableSubagents = owner, onTranscript = onTranscript)

    @Test fun approvedAcceptedChildDoesNotBlockIndependentApprovedStepAndReportOnlyAppends() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))),
            PlanStep("independent", "Independent", actions = listOf(action("terminal", writeArguments))))
        val persistence = Persistence()
        val release = CountDownLatch(1)
        val independent = CountDownLatch(1)
        val naturalAnswer = CountDownLatch(1)
        val reportReceived = CountDownLatch(1)
        val snapshots = Collections.synchronizedList(mutableListOf<ProviderRequest>())
        val launches = AtomicInteger()
        val owner = YieldableSubagents(controller, persistence) { _, started ->
            launches.incrementAndGet(); started("child-1", "")
            check(release.await(5, TimeUnit.SECONDS))
            completedChild("complete-child-report")
        }
        val model = provider { request, round ->
            snapshots += request.copy(messages = JSONArray(request.messages.toString()), tools = JSONArray(request.tools.toString()))
            when (round) {
                1 -> tools(begin("child"), call("launch", "subagent", childArguments))
                2 -> {
                    assertEquals(YieldableSubagents.Decision.WAIT, owner.decision())
                    val accepted = request.messages.getJSONObject(request.messages.length() - 1)
                    assertEquals("launch", accepted.getString("tool_call_id"))
                    assertEquals("accepted", JSONObject(accepted.getString("content")).getString("status"))
                    tools(begin("independent"), call("write", "terminal", writeArguments))
                }
                3 -> answer("independent work complete")
                4 -> { reportReceived.countDown(); answer("all work complete") }
                else -> error("parent must not poll")
            }
        }
        val result = AtomicReference<AgentModelClient.ModelResponse.Text?>()
        val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try {
                result.set(complete(model, owner, AgentModelClient.ToolExecutor {
                    assertEquals("write", it.id); independent.countDown()
                    AgentModelClient.ToolResult("""{"ok":true,"exit_code":0}""")
                }) { transcript -> if (transcript.any { it.content == "independent work complete" }) naturalAnswer.countDown() })
            } catch (t: Throwable) { failure.set(t) }
        }
        try {
            runner.start()
            assertTrue("accepted must return to parent model before waiting", independent.await(2, TimeUnit.SECONDS))
            assertTrue(naturalAnswer.await(2, TimeUnit.SECONDS))
            assertFalse(reportReceived.await(150, TimeUnit.MILLISECONDS))
            assertTrue(runner.isAlive)
            assertEquals(3, snapshots.size)
            controller.pause()
            release.countDown()
            assertFalse("a report must not bypass a paused parent", reportReceived.await(150, TimeUnit.MILLISECONDS))
            controller.resume(); runner.join(5000)
            assertFalse(runner.isAlive); failure.get()?.let { throw AssertionError(it) }
            assertEquals("all work complete", result.get()!!.content)
            assertEquals(1, launches.get()); assertEquals(1, persistence.starts.get()); assertEquals(1, persistence.finishes.get())
            assertEquals(1, result.get()!!.transcript.count { it.messageId.startsWith("subagent-report-") })
            val before = snapshots[2].messages
            val after = snapshots[3].messages
            for (index in 0 until before.length()) assertEquals(before.get(index).toString(), after.get(index).toString())
            assertTrue(after.toString().contains("不是终端用户发言或授权"))
            assertTrue(after.toString().contains("complete-child-report"))
            assertEquals(1, snapshots.map { it.tools.toString() }.distinct().size)
            assertEquals(listOf("stable-session"), snapshots.map { it.sessionId }.distinct())
            assertEquals(1, snapshots.map { request -> JSONObject().also { OpenAiPromptCache.apply(it, request) }
                .getString("prompt_cache_key") }.distinct().size)
            assertEquals("completed", PlanModeStore.current("integration-run")!!.steps.first { it.id == "independent" }.status)
        } finally { release.countDown(); controller.cancel(); owner.close(); runner.join(5000) }
    }

    @Test fun jointReportSettlesEachOriginalApprovedChildRatherThanGroupKey() {
        val argsA = JSONObject(childArguments.toString()).put("group_key", "batch").put("description", "a")
        val argsB = JSONObject(childArguments.toString()).put("group_key", "batch").put("description", "b")
        approve(PlanStep("a", "A", actions = listOf(action("subagent", argsA))),
            PlanStep("b", "B", actions = listOf(action("subagent", argsB))))
        val owner = YieldableSubagents(controller, Persistence(), parentRunId = "integration-run") { call, started ->
            started("child-${call.id}", "")
            if (call.id == "launch-a") completedChild("a-complete") else {
                val semantic = io.github.mangi.eta.local.subagentcontext.SubagentTaskResult.fromFinal(
                    "task_status: partial\nsummary: b-partial\nevidence: fixture:2\nremaining: device verification\nnext_action: verify")
                AgentModelClient.ToolResult(semantic.attach(JSONObject().put("ok", true).put("status", "completed")).toString())
            }
        }
        val model = provider { _, round -> when (round) {
            1 -> tools(begin("a"), call("launch-a", "subagent", argsA))
            2 -> tools(begin("b"), call("launch-b", "subagent", argsB))
            3 -> answer("no independent work remains")
            4 -> answer("joint inspected")
            else -> error("extra model call")
        } }
        try {
            val response = AgentModelClient.complete(config, "goal", AgentModelClient.ToolExecutor { error("not delegated") },
                provider = model, runController = controller, planGate = gate, yieldableSubagents = owner,
                additionalTools = schemas(groupsSupported = true))
            assertEquals(1, response.transcript.count { it.messageId.startsWith("subagent-report-group-") })
            val plan = PlanModeStore.current("integration-run")!!
            assertEquals("completed", plan.steps.first { it.id == "a" }.status)
            assertNotEquals("completed", plan.steps.first { it.id == "b" }.status)
            assertTrue(plan.steps.none { it.status == "waiting_child" })
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun unansweredQuestionWithYieldableOwnerBlocksSameBatchLaunchAndWriteButKeepsIndependentApproval() {
        approve(PlanStep("child", "Inspect", independentOfQuestions = true, actions = listOf(action("subagent", childArguments))),
            PlanStep("independent", "Independent", independentOfQuestions = true, actions = listOf(action("terminal", writeArguments))))
        val persistence = Persistence()
        val owner = YieldableSubagents(controller, persistence) { _, _ -> error("same-batch child must not start") }
        val delegated = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val model = provider { request, round ->
            requests += request.messages.toString()
            when (round) {
                1 -> tools(begin("child"), call("question", "ask_user_questions"), call("stale-child", "subagent", childArguments),
                    call("stale-write", "terminal", writeArguments))
                2 -> tools(begin("independent"), call("independent-write", "terminal", writeArguments))
                3 -> answer("only independent work completed")
                else -> error("unexpected request")
            }
        }
        try {
            val result = complete(model, owner, AgentModelClient.ToolExecutor {
                delegated += it.id
                AgentModelClient.ToolResult(if (it.name == "ask_user_questions") """{"ok":false,"status":"unanswered"}"""
                    else """{"ok":true,"exit_code":0}""")
            })
            assertEquals(listOf("question", "independent-write"), delegated)
            assertEquals(0, persistence.starts.get())
            for (id in listOf("stale-child", "stale-write")) assertTrue(result.transcript.any {
                it.role == "tool" && it.toolCallId == id && it.content.contains("PLAN_QUESTION_BOUNDARY")
            })
            assertTrue(requests[1].contains("unanswered"))
            val plan = PlanModeStore.current("integration-run")!!
            assertEquals(1, plan.approvedRevision); assertTrue(plan.questionBlocked)
            assertTrue(plan.independenceApproved)
            assertEquals("pending", plan.steps.first { it.id == "child" }.status)
            assertEquals("completed", plan.steps.first { it.id == "independent" }.status)
        } finally { owner.close() }
    }

    @Test fun exactApprovedLaunchCannotBeReplacedByDifferentPromptOrChildProfile() {
        approve(PlanStep("child", "Exact launch", actions = listOf(action("subagent", childArguments))))
        val persistence = Persistence()
        val owner = YieldableSubagents(controller, persistence) { _, _ -> error("changed launch must not dispatch") }
        val changed = JSONObject(childArguments.toString()).put("prompt", "unapproved task").put("subagent_type", "coder")
        val model = provider { _, round ->
            when (round) {
                1 -> tools(begin("child"), call("changed-child", "subagent", changed))
                2 -> answer("exact launch still pending")
                else -> error("unexpected request")
            }
        }
        try {
            val result = complete(model, owner, AgentModelClient.ToolExecutor { error("no delegate") })
            assertEquals(0, persistence.starts.get())
            assertTrue(result.transcript.any { it.toolCallId == "changed-child" && it.content.contains("PLAN_ACTION_MISMATCH") })
            assertTrue(PlanModeStore.current("integration-run")!!.uncertainActions.isEmpty())
        } finally { owner.close() }
    }

    @Test fun yieldableLoopNeverAuthorizesChangedParametersOrReplaysUnknownSideEffect() {
        approve(PlanStep("write", "Unknown result", actions = listOf(action("terminal", writeArguments))))
        val owner = YieldableSubagents(controller, Persistence()) { _, _ -> error("no child expected") }
        val writes = AtomicInteger()
        val model = provider { _, round ->
            when (round) {
                1 -> tools(begin("write"), call("unknown", "terminal", writeArguments))
                2 -> tools(call("repeat", "terminal", writeArguments),
                    call("changed", "terminal", JSONObject().put("command", "unapproved")))
                3 -> answer("verify unknown result before retry")
                else -> error("unexpected request")
            }
        }
        try {
            val result = complete(model, owner, AgentModelClient.ToolExecutor {
                writes.incrementAndGet()
                AgentModelClient.ToolResult("""{"ok":false,"code":"ACTION_OUTCOME_UNKNOWN"}""")
            })
            assertEquals(1, writes.get())
            for (id in listOf("repeat", "changed")) assertTrue(result.transcript.any {
                it.toolCallId == id && it.content.contains("PLAN_STEP_BLOCKED")
            })
            assertEquals(1, PlanModeStore.current("integration-run")!!.uncertainActions.size)
        } finally { owner.close() }
    }

    @Test fun completedChildReportUnblocksApprovedDependentStepWithoutRelaunchingChild() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))),
            PlanStep("dependent", "After inspection", dependsOn = listOf("child"), actions = listOf(action("terminal", writeArguments))))
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val persistence = Persistence()
        val writes = AtomicInteger()
        val owner = YieldableSubagents(controller, persistence) { _, started ->
            started("child-1", ""); check(release.await(5, TimeUnit.SECONDS))
            completedChild("verified-child-report")
        }
        val model = provider { request, round ->
            when (round) {
                1 -> tools(begin("child"), call("launch", "subagent", childArguments))
                2 -> answer("wait for inspection")
                3 -> {
                    assertTrue(request.messages.toString().contains("verified-child-report"))
                    tools(begin("dependent"), call("dependent-write", "terminal", writeArguments))
                }
                4 -> answer("dependent work complete")
                else -> error("unexpected request")
            }
        }
        val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try { complete(model, owner, AgentModelClient.ToolExecutor {
                writes.incrementAndGet(); AgentModelClient.ToolResult("""{"ok":true,"exit_code":0}""")
            }) { transcript -> if (transcript.any { it.content == "wait for inspection" }) waiting.countDown() } }
            catch (t: Throwable) { failure.set(t) }
        }
        try {
            runner.start(); assertTrue(waiting.await(2, TimeUnit.SECONDS)); release.countDown(); runner.join(5000)
            assertFalse(runner.isAlive); failure.get()?.let { throw AssertionError(it) }
            // The exact launch action must not remain poisoned after its child has completed.
            assertEquals("successful child must not leave its approved launch permanently blocked", "completed",
                PlanModeStore.current("integration-run")!!.steps.first { it.id == "child" }.status)
            assertEquals(1, writes.get()); assertEquals(1, persistence.starts.get())
            assertTrue(PlanModeStore.current("integration-run")!!.uncertainActions.isEmpty())
        } finally { release.countDown(); controller.cancel(); owner.close(); runner.join(5000) }
    }

    @Test fun approvedLaunchReceiptAllowsNextExactIndependentActionInSameStepBeforeChildReport() {
        approve(PlanStep("combined", "Launch then independent work", actions = listOf(
            action("subagent", childArguments), action("terminal", writeArguments))))
        val release = CountDownLatch(1)
        val persistence = Persistence()
        val writes = AtomicInteger()
        val owner = YieldableSubagents(controller, persistence) { _, started ->
            started("same-step-child", ""); check(release.await(5, TimeUnit.SECONDS))
            completedChild("same-step-report")
        }
        val model = provider { request, round ->
            when (round) {
                1 -> tools(begin("combined"), call("launch", "subagent", childArguments))
                2 -> {
                    assertTrue(request.messages.toString().contains("accepted"))
                    assertEquals(0, persistence.finishes.get())
                    tools(call("same-step-independent", "terminal", writeArguments))
                }
                3 -> { release.countDown(); answer("independent work handled") }
                4 -> answer("report consumed")
                else -> error("unexpected request")
            }
        }
        try {
            complete(model, owner, AgentModelClient.ToolExecutor {
                assertEquals("same-step-independent", it.id)
                assertEquals("independent work must run before child report", 0, persistence.finishes.get())
                writes.incrementAndGet(); AgentModelClient.ToolResult("""{"ok":true,"exit_code":0}""")
            })
            assertEquals("durable accepted launch must not poison its remaining approved action list", 1, writes.get())
            assertEquals(1, persistence.starts.get())
        } finally { release.countDown(); controller.cancel(); owner.close() }
    }

    @Test fun acceptedLaunchDoesNotReleaseDependentStepBeforeTypedFinalReport() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))),
            PlanStep("dependent", "Needs result", dependsOn = listOf("child"), actions = listOf(action("terminal", writeArguments))))
        assertTrue(JSONObject(gate.execute(AgentModelClient.ToolCall(round = 1, id = "begin", name = PlanRunGate.BEGIN, argumentsJson =
            JSONObject().put("step_id", "child").put("revision", 1).toString())) { error("begin is internal") }.content).getBoolean("ok"))
        val receipt = JSONObject().put("ok", true).put("status", "accepted").put("task_id", "owned-task").put("subagent_id", "child-id")
        gate.execute(AgentModelClient.ToolCall(round = 2, id = "launch", name = "subagent", argumentsJson = childArguments.toString()), knownChildLaunch = { true }) {
            AgentModelClient.ToolResult(receipt.toString())
        }
        assertEquals("waiting_child", PlanModeStore.current("integration-run")!!.steps.first().status)
        val dependent = AgentModelClient.ToolCall(round = 3, id = "begin-dependent", name = PlanRunGate.BEGIN, argumentsJson =
            JSONObject().put("step_id", "dependent").put("revision", 1).toString())
        assertTrue(gate.execute(dependent) { error("not delegated") }.content.contains("PLAN_STEP_BLOCKED"))
        gate.childReport("unrelated-task", true)
        assertEquals("waiting_child", PlanModeStore.current("integration-run")!!.steps.first().status)
        gate.childReport("owned-task", true)
        assertEquals("completed", PlanModeStore.current("integration-run")!!.steps.first().status)
        assertTrue(JSONObject(gate.execute(dependent) { error("begin is internal") }.content).getBoolean("ok"))
    }

    @Test fun childFailureOrRevisedPlanNeverCompletesAnUnrelatedApprovedStep() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))))
        gate.execute(AgentModelClient.ToolCall(round = 1, id = "begin", name = PlanRunGate.BEGIN, argumentsJson =
            JSONObject().put("step_id", "child").put("revision", 1).toString())) { error("internal") }
        val receipt = JSONObject().put("ok", true).put("status", "accepted").put("task_id", "owned-task").put("subagent_id", "child-id")
        gate.execute(AgentModelClient.ToolCall(round = 2, id = "launch", name = "subagent", argumentsJson = childArguments.toString()), knownChildLaunch = { true }) {
            AgentModelClient.ToolResult(receipt.toString())
        }
        gate.childReport("owned-task", false)
        assertEquals("blocked", PlanModeStore.current("integration-run")!!.steps.first().status)
        assertEquals(1, PlanModeStore.current("integration-run")!!.uncertainActions.size)
        gate.childReport("owned-task", true) // duplicate late success cannot override a failed receipt
        assertEquals("blocked", PlanModeStore.current("integration-run")!!.steps.first().status)
        val p = PlanModeStore.current("integration-run")!!
        assertTrue(PlanModeStore.edit(p.planId, p.revision, "Changed scope"))
        gate.childReport("owned-task", true)
        assertNull(PlanModeStore.current("integration-run")!!.approvedRevision)
        assertTrue(PlanModeStore.current("integration-run")!!.steps.isEmpty())
    }

    @Test fun unownedAcceptedJsonNeverCountsAsSuccessfulLaunchDispatch() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))))
        gate.execute(AgentModelClient.ToolCall(round = 1, id = "begin", name = PlanRunGate.BEGIN, argumentsJson =
            JSONObject().put("step_id", "child").put("revision", 1).toString())) { error("internal") }
        gate.execute(AgentModelClient.ToolCall(round = 2, id = "fake-launch", name = "subagent", argumentsJson = childArguments.toString())) {
            AgentModelClient.ToolResult("""{"ok":true,"status":"accepted","task_id":"made-up","subagent_id":"made-up"}""")
        }
        assertEquals("blocked", PlanModeStore.current("integration-run")!!.steps.first().status)
        assertEquals(1, PlanModeStore.current("integration-run")!!.uncertainActions.size)
    }

    @Test fun cancellingApprovedRunWhileNaturallyWaitingDoesNotRestartOrPublishChildCompletion() {
        approve(PlanStep("child", "Inspect", actions = listOf(action("subagent", childArguments))))
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persistence = Persistence()
        val rounds = AtomicInteger()
        val launches = AtomicInteger()
        val owner = YieldableSubagents(controller, persistence) { _, started ->
            launches.incrementAndGet(); started("cancel-child", "")
            check(release.await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult("""{"ok":true,"status":"completed"}""")
        }
        val model = provider { _, round ->
            rounds.incrementAndGet()
            when (round) {
                1 -> tools(begin("child"), call("launch", "subagent", childArguments))
                2 -> answer("naturally waiting")
                else -> error("cancelled run must not re-request provider")
            }
        }
        val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try { complete(model, owner, AgentModelClient.ToolExecutor { error("no parent tool expected") }) {
                transcript -> if (transcript.any { it.content == "naturally waiting" }) waiting.countDown()
            } } catch (t: Throwable) { failure.set(t) }
        }
        try {
            runner.start(); assertTrue(waiting.await(2, TimeUnit.SECONDS))
            controller.cancel(); release.countDown(); runner.join(5000)
            assertFalse(runner.isAlive)
            assertNotNull("cancel must terminate instead of succeeding", failure.get())
            assertEquals(2, rounds.get()); assertEquals(1, launches.get())
            assertEquals(0, persistence.finishes.get())
            assertTrue(persistence.interrupted.get() > 0)
        } finally { release.countDown(); controller.cancel(); owner.close(); runner.join(5000) }
    }
}
