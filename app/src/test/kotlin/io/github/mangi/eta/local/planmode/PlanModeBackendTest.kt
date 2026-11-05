package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.local.userquestions.QuestionDraft
import io.github.mangi.eta.local.userquestions.UserQuestionStore
import io.github.mangi.eta.local.userquestions.UserQuestionHost
import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class PlanModeBackendTest {
    private lateinit var dir: java.io.File
    private lateinit var controller: AgentRunController
    private lateinit var gate: PlanRunGate
    private val executor = Executors.newSingleThreadExecutor()
    private val questions = JSONObject("""{"questions":[{"prompt":"Q"}]}""")
    private fun call(name: String, args: String, round: Int = 1) = AgentModelClient.ToolCall("c-$round", name, args, round)
    private fun code(result: AgentModelClient.ToolResult) = JSONObject(result.content).optString("code")
    private fun ok() = AgentModelClient.ToolResult("""{"ok":true}""")
    private fun attempt(name: String = "terminal", args: String = """{"command":"echo ok"}""", round: Int = 3) =
        gate.execute(call(name, args, round)) { ok() }
    private fun proposal(independent: Boolean = false) = """{"title":"实施","content":"目标、边界、验证","steps":[{"id":"one","title":"第一步","independent_of_questions":$independent,"actions":[{"tool_name":"terminal","arguments":{"command":"echo ok"}}]},{"id":"two","title":"第二步","depends_on":["one"],"actions":[{"tool_name":"mcp_write","arguments":{"value":1}}]}]}"""
    private fun openProposal(round: Int = 1, independent: Boolean = false): java.util.concurrent.Future<AgentModelClient.ToolResult> {
        val f = executor.submit<AgentModelClient.ToolResult> { gate.execute(call(PlanRunGate.PROPOSE, proposal(independent), round)) { error("delegate") } }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (PlanModeStore.current("run")?.status != "awaiting_approval" && System.nanoTime() < limit) Thread.sleep(2)
        assertEquals("awaiting_approval", PlanModeStore.current("run")?.status)
        return f
    }
    private fun approve(f: java.util.concurrent.Future<AgentModelClient.ToolResult>) {
        val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_APPROVED", code(f.get(2, TimeUnit.SECONDS)))
    }
    private fun begin(id: String, revision: Int, round: Int = 2) = gate.execute(
        call(PlanRunGate.BEGIN, """{"step_id":"$id","revision":$revision}""", round)) { error("delegate") }

    @Before fun setup() {
        dir = Files.createTempDirectory("plan-backend-test").toFile()
        PlanModeStore.resetForTest(); UserQuestionStore.resetForTest()
        PlanModeStore.initializeAt(dir); UserQuestionStore.initializeAt(dir)
        controller = AgentRunController(); gate = PlanRunGate("chat", "run", controller)
        PlanModeStore.listen("run", controller::signalWorkAvailable) { !controller.isCancelled && !controller.hasPendingSteering }
    }
    @After fun cleanup() {
        controller.cancel(); executor.shutdownNow()
        PlanModeStore.resetForTest(); UserQuestionStore.resetForTest(); dir.deleteRecursively()
    }
    @Test fun noApprovalBlocksEverySideEffectAndChildBypass() {
        for (name in listOf("terminal", "browser_use", "mcp_write", "subagent"))
            assertEquals("PLAN_APPROVAL_REQUIRED", code(attempt(name)))
        assertEquals("PLAN_CHILD_BLOCKED", code(gate.executeChild(call("terminal", "{}")) { ok() }))
        assertTrue(JSONObject(gate.executeChild(call("read_file", "{}")) { ok() }.content).getBoolean("ok"))
    }
    @Test fun exactArgumentsAndOrderAndDependenciesAreEnforced() {
        val f = openProposal(); approve(f)
        assertEquals("PLAN_TURN_BOUNDARY", code(begin("one", 1, 1)))
        assertEquals("PLAN_STEP_BLOCKED", code(begin("two", 1)))
        assertEquals("PLAN_STEP_READY", code(begin("one", 1)))
        assertEquals("PLAN_ACTION_MISMATCH", code(attempt(args = """{"command":"rm -rf /"}""")))
        assertEquals("PLAN_ACTION_MISMATCH", code(attempt(name = "browser_use")))
        assertTrue(JSONObject(attempt().content).getBoolean("ok"))
        assertEquals("PLAN_STEP_BLOCKED", code(begin("one", 1)))
        assertEquals("PLAN_STEP_READY", code(begin("two", 1)))
        assertTrue(JSONObject(attempt("mcp_write", """{"value":1}""").content).getBoolean("ok"))
    }
    @Test fun editRejectsOldApprovalAndInFlightApprovalCannotCarryOver() {
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.edit(p.planId, p.revision, "本人修改"))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_CHANGED", code(f.get(2, TimeUnit.SECONDS)))
        assertEquals("PLAN_EDIT_PENDING", code(attempt()))
        assertTrue(gate.editedPlanMessage()!!.contains("本人修改"))
        assertEquals("PLAN_APPROVAL_REQUIRED", code(attempt()))
        assertTrue(PlanModeStore.current("run")!!.steps.isEmpty())
    }
    @Test fun approvalPhasePreservesQuestionBlockEvenWithApprovedIndependentAction() {
        var priorRevision: Int? = null
        for (blocked in listOf(false, true)) {
            val plan = PlanModeStore.propose("chat", "run", "phase", "content",
                listOf(PlanStep("one", "independent", independentOfQuestions = true,
                    actions = listOf(PlanAction("terminal", "{}")))),
                questionBlocked = blocked, expectedRevision = priorRevision)
            assertTrue(PlanModeStore.approve(plan.planId, plan.revision))
            val approved = PlanModeStore.current("run")!!
            assertEquals(plan.revision, approved.approvedRevision)
            assertTrue(approved.independenceApproved)
            assertEquals(if (blocked) "partially_blocked" else "running", approved.phase)
            priorRevision = approved.revision
        }
    }

    @Test fun questionBoundaryAndExplicitReapprovalOfIndependentSteps() {
        val f = openProposal(); approve(f)
        gate.noteQuestion(4, """{"status":"unanswered"}""")
        assertEquals("PLAN_TURN_BOUNDARY", code(begin("one", 1, 4)))
        assertEquals("PLAN_STEP_BLOCKED", code(begin("one", 1, 5)))
        val f2 = openProposal(6, independent = true); approve(f2)
        val p = PlanModeStore.current("run")!!
        assertTrue(p.questionBlocked && p.independenceApproved)
        assertEquals("PLAN_STEP_READY", code(begin("one", p.revision, 7)))
        assertTrue(JSONObject(attempt(round = 8).content).getBoolean("ok"))
        assertEquals("PLAN_STEP_BLOCKED", code(begin("two", p.revision, 9)))
    }
    @Test fun unknownNetworkSideEffectNeverAutomaticallyRepeats() {
        val f = openProposal(); approve(f); begin("one", 1)
        var count = 0
        val call = call("terminal", """{"command":"echo ok"}""", 3)
        val result = gate.execute(call) { count++; AgentModelClient.ToolResult("""{"ok":false,"code":"ACTION_OUTCOME_UNKNOWN"}""") }
        assertEquals("ACTION_OUTCOME_UNKNOWN", code(result)); assertEquals(1, count)
        assertEquals("PLAN_STEP_BLOCKED", code(gate.execute(call) { count++; ok() }))
        assertEquals(1, count)
    }
    @Test fun steeringWakesApprovalAndInvalidatesOldWork() {
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        assertTrue(controller.steer("换方向"))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_UNANSWERED", code(f.get(2, TimeUnit.SECONDS)))
        gate.onSteering()
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_STEERING_PENDING", code(attempt()))
    }
    @Test fun endedAndRestartedRecordsAreNotExecutionLeases() {
        val f = openProposal(); approve(f)
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        val restored = PlanModeStore.plans.value.single()
        assertFalse(restored.active); assertNull(restored.approvedRevision)
        assertFalse(PlanModeStore.approve(restored.planId, restored.revision))
        assertEquals("PLAN_APPROVAL_REQUIRED", code(attempt()))
    }
    @Test fun questionIdentityConversationRunHostCallAndPlanRevision() {
        val a = UserQuestionStore.open("a", "run", "call", questions, "parent", "plan", 1)
        val duplicate = UserQuestionStore.open("a", "run", "call", questions, "parent", "plan", 1)
        assertEquals(a.first, duplicate.first)
        val ids = listOf(a.first,
            UserQuestionStore.open("b", "run", "call", questions, "parent", "plan", 1).first,
            UserQuestionStore.open("a", "run", "call", questions, "child", "plan", 1).first,
            UserQuestionStore.open("a", "run", "call", questions, "parent", "plan", 2).first,
            UserQuestionStore.open("a", "other-run", "call", questions, "parent", "plan", 1).first)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(UserQuestionStore.submit(a.first, listOf(QuestionDraft(other = "yes"))))
        assertFalse(UserQuestionStore.submit(a.first, listOf(QuestionDraft(other = "late"))))
    }
    @Test fun lateExpiredRevisedAndCancelledAnswersAreRejected() {
        var revision = 1
        val expired = UserQuestionStore.open("a", "r", "expired", questions, timeoutMs = 1)
        Thread.sleep(5)
        assertFalse(UserQuestionStore.submit(expired.first, listOf(QuestionDraft(other = "late"))))
        val revised = UserQuestionStore.open("a", "r", "revised", questions, planRevision = 1) { revision == 1 }
        revision = 2
        assertFalse(UserQuestionStore.submit(revised.first, listOf(QuestionDraft(other = "old"))))
        UserQuestionStore.dismiss(revised.first)
        assertFalse(UserQuestionStore.submit(revised.first, listOf(QuestionDraft(other = "after end"))))
    }
    @Test fun questionHostIsEventDrivenAndLateTimeoutCannotSubmit() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = dir }
        val host = UserQuestionHost(context, "chat", "run", controller, timeoutMs = 40)
        val future = executor.submit<String> { host.ask("timeout", questions) }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (UserQuestionStore.forms.value.none { it.callId == "timeout" } && System.nanoTime() < limit) Thread.sleep(2)
        val id = UserQuestionStore.forms.value.single { it.callId == "timeout" }.id
        assertEquals("unanswered", JSONObject(future.get(2, TimeUnit.SECONDS)).getString("status"))
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft(other = "too late"))))
        assertEquals("expired", UserQuestionStore.forms.value.single { it.id == id }.status)
        assertTrue(UserQuestionStore.forms.value.single { it.id == id }.endReason.contains("超时"))
    }
    @Test fun questionWaitWakesOnSteeringAndRejectsPendingReply() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = dir }
        val host = UserQuestionHost(context, "chat", "run", controller, timeoutMs = 5000)
        val future = executor.submit<String> { host.ask("steer", questions) }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (UserQuestionStore.forms.value.none { it.callId == "steer" } && System.nanoTime() < limit) Thread.sleep(2)
        val id = UserQuestionStore.forms.value.single { it.callId == "steer" }.id
        controller.steer("new direction")
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft(other = "old"))))
        assertEquals("unanswered", JSONObject(future.get(2, TimeUnit.SECONDS)).getString("status"))
    }
    @Test fun editedInFlightActionReportsUnknownAndCannotReplayInSameRun() {
        val first = openProposal(); approve(first); begin("one", 1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val inflight = executor.submit<AgentModelClient.ToolResult> {
            gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) { entered.countDown(); release.await(); ok() }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.edit(p.planId, p.revision, "new plan"))
        assertTrue(PlanModeStore.current("run")!!.interruptedWork)
        release.countDown()
        assertEquals("PLAN_CHANGED_AFTER_IO", code(inflight.get(2, TimeUnit.SECONDS)))
        assertTrue(gate.editedPlanMessage()!!.contains("new plan"))
        val second = openProposal(4); approve(second)
        val revision = PlanModeStore.current("run")!!.revision
        begin("one", revision, 5)
        assertEquals("PLAN_ACTION_ALREADY_ATTEMPTED", code(attempt(round = 6)))
    }
    @Test fun childAfterRunEndedFailsClosed() {
        gate.end()
        assertEquals("PLAN_CHILD_BLOCKED", code(gate.executeChild(call("read_file", "{}")) { ok() }))
    }

    @Test fun cancelledQuestionReturnsPromptlyAndCannotBeAnswered() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = dir }
        val host = UserQuestionHost(context, "chat", "run", controller, timeoutMs = 5000)
        val future = executor.submit<String> { host.ask("cancel", questions) }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (UserQuestionStore.forms.value.none { it.callId == "cancel" } && System.nanoTime() < limit) Thread.sleep(2)
        val id = UserQuestionStore.forms.value.single { it.callId == "cancel" }.id
        controller.cancel()
        assertTrue(runCatching { future.get(2, TimeUnit.SECONDS) }.isFailure)
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft(other = "after stop"))))
    }
    @Test fun explicitApprovalExpiryAndDifferentConversationsAreIsolated() {
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        PlanModeStore.expireApproval("run", p.revision)
        assertEquals("PLAN_UNANSWERED", code(f.get(2, TimeUnit.SECONDS)))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        val other = PlanModeStore.propose("other-chat", "other-run", "other", "content",
            listOf(PlanStep("x", "other", actions = listOf(PlanAction("terminal", "{}")))))
        assertNotEquals(p.planId, other.planId)
        assertEquals("other-chat", PlanModeStore.current("other-run")!!.conversationId)
        assertEquals("chat", PlanModeStore.current("run")!!.conversationId)
        assertTrue(runCatching { PlanModeStore.propose("other-chat", "run", "x", "content", other.steps, expectedRevision = p.revision) }.isFailure)
    }
    @Test fun networkFailureNeverAutomaticallyReplaysAnMcpAction() {
        val f = openProposal(); approve(f); begin("one", 1); attempt(); begin("two", 1)
        val call = call("mcp_write", """{"value":1}""", 4)
        var sent = 0
        assertEquals("MCP_CALL_FAILED", code(gate.execute(call) { sent++; AgentModelClient.ToolResult("""{"ok":false,"code":"MCP_CALL_FAILED"}""") }))
        assertEquals("PLAN_STEP_BLOCKED", code(gate.execute(call) { sent++; ok() }))
        assertEquals(1, sent)
    }

    @Test fun approvalHasTenMinuteMonotonicLeaseAndQuestionsRemainOneMinute() {
        assertEquals(600_000L, PlanModeStore.APPROVAL_WAIT_MS)
        assertEquals(60_000L, UserQuestionStore.QUESTION_WAIT_MS)
        var now = 1_000_000_000L
        PlanModeStore.setClockForTest { now }
        val future = openProposal(); val p = PlanModeStore.current("run")!!
        assertEquals(600_000L, PlanModeStore.approvalRemainingMs("run", p.revision))
        now += TimeUnit.SECONDS.toNanos(599)
        assertEquals(1_000L, PlanModeStore.approvalRemainingMs("run", p.revision))
        now += TimeUnit.SECONDS.toNanos(1)
        assertEquals(0L, PlanModeStore.approvalRemainingMs("run", p.revision))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        PlanModeStore.expireApproval("run", p.revision)
        assertEquals("PLAN_UNANSWERED", code(future.get(2, TimeUnit.SECONDS)))
    }
    @Test fun failedApprovalDoesNotPersistAndCanBeRetriedDuplicateDoesNotPublish() {
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        val file = java.io.File(dir, "planmode/plans.json")
        val bytes = file.readBytes()
        PlanModeStore.failNextWriteForTest()
        assertTrue(runCatching { PlanModeStore.approve(p.planId, p.revision) }.isFailure)
        assertEquals(p, PlanModeStore.current("run"))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(PlanModeStore.approvalRemainingMs("run", p.revision) > 0)
        assertTrue(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_APPROVED", code(f.get(2, TimeUnit.SECONDS)))
        PlanModeStore.failNextWriteForTest()
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        // Failure remains armed: duplicate approval did not publish.
        assertTrue(runCatching { PlanModeStore.end("run") }.isFailure)
        assertNull(PlanModeStore.current("run"))
        assertFalse(PlanModeStore.plans.value.single().active)
        assertNull(PlanModeStore.plans.value.single().approvedRevision)
        assertTrue(PlanModeStore.plans.value.single().persistenceWarning.contains("保存未确认"))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        // The failed write is not claimed durable; reload still refuses the old lease.
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        assertFalse(PlanModeStore.plans.value.single().active)
        assertNull(PlanModeStore.plans.value.single().approvedRevision)
    }
    @Test fun failedProposalOrEditPreservesPriorApprovalAndAtomicSnapshot() {
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        val bytes = java.io.File(dir, "planmode/plans.json").readBytes()
        PlanModeStore.failNextWriteForTest()
        assertTrue(runCatching { PlanModeStore.propose("chat", "run", "replacement", "content", p.steps, expectedRevision = p.revision) }.isFailure)
        assertEquals(p, PlanModeStore.current("run"))
        assertArrayEquals(bytes, java.io.File(dir, "planmode/plans.json").readBytes())
        assertTrue(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_APPROVED", code(f.get(2, TimeUnit.SECONDS)))
        val approved = PlanModeStore.current("run")!!
        PlanModeStore.failNextWriteForTest()
        assertTrue(runCatching { PlanModeStore.edit(p.planId, p.revision, "new") }.isFailure)
        assertEquals(approved, PlanModeStore.current("run"))
        assertEquals("PLAN_STEP_READY", code(begin("one", p.revision)))
        assertTrue(JSONObject(attempt().content).getBoolean("ok"))
    }
    @Test fun reservationFailureNeverDispatchesAndResultWriteFailureRemainsUnknownAfterRestart() {
        val f = openProposal(); approve(f); begin("one", 1)
        val before = PlanModeStore.current("run")!!
        var dispatched = 0
        PlanModeStore.failNextWriteForTest()
        assertTrue(runCatching { gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) { dispatched++; ok() } }.isFailure)
        assertEquals(0, dispatched)
        assertEquals(before, PlanModeStore.current("run"))
        assertEquals("PLAN_RESULT_RECORD_FAILED", code(gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) {
            dispatched++; PlanModeStore.failNextWriteForTest(); ok()
        }))
        assertEquals(1, dispatched)
        assertEquals("running", PlanModeStore.current("run")!!.steps.first().status)
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        val restored = PlanModeStore.plans.value.single()
        assertFalse(restored.active); assertNull(restored.approvedRevision)
        assertEquals("partially_blocked", restored.phase); assertEquals("blocked", restored.steps.first().status)
        assertTrue(restored.interruptedWork); assertEquals(1, restored.uncertainActions.size)
    }
    @Test fun staleReservationRejectsNewlyBlockedQuestionAndConsumedSteering() {
        val f = openProposal(); approve(f)
        val p = PlanModeStore.current("run")!!; val s = p.steps.first(); val action = s.actions.single()
        gate.noteQuestion(2, """{"status":"unanswered"}""")
        assertFalse(PlanModeStore.reserveAction("run", p.revision, s, action, action.fingerprint()) { gate.mayAct() })
        assertEquals("pending", PlanModeStore.current("run")!!.steps.first().status)
        assertTrue(controller.steer("new"))
        val pending = controller.pollSteeringInput()!!
        assertFalse(controller.hasPendingSteering)
        assertFalse(gate.mayAct()) // consumed from queue is not yet delivered to the model
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        gate.onSteering(pending.sequence)
        assertTrue(gate.mayAct())
        assertNull(PlanModeStore.current("run")!!.approvedRevision)
    }
    @Test fun listenerFailureDoesNotTurnDurableApprovalIntoFailureOrDuplicateWrite() {
        PlanModeStore.listen("other", { error("test listener failure") })
        val f = openProposal(); val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_APPROVED", code(f.get(2, TimeUnit.SECONDS)))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        assertEquals(p.revision, PlanModeStore.current("run")!!.approvedRevision)
    }
    @Test fun nonzeroTerminalExitNeverCompletesTheStepDespiteOkTrue() {
        val f = openProposal(); approve(f); begin("one", 1)
        val result = gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) {
            AgentModelClient.ToolResult("""{"ok":true,"status":"exited","exit_code":7}""")
        }
        assertEquals("PLAN_TERMINAL_EXIT_NONZERO", code(result))
        assertEquals(7, JSONObject(result.content).getJSONObject("tool_result").getInt("exit_code"))
        assertEquals("blocked", PlanModeStore.current("run")!!.steps.first().status)
        assertEquals("PLAN_STEP_BLOCKED", code(begin("two", 1, 4)))
    }
    @Test fun planningPureQueriesAllowedButBrowserShellAndMcpRequireApproval() {
        for (name in listOf("search_files", "search_contacts", "read_personal_item")) {
            var count = 0
            assertTrue(JSONObject(gate.execute(call(name, "{}")) { count++; ok() }.content).getBoolean("ok"))
            assertEquals(1, count)
        }
        for (name in listOf("terminal", "browser_use", "mcp_read", "mcp_write")) {
            assertEquals("PLAN_APPROVAL_REQUIRED", code(attempt(name)))
        }
    }
    @Test fun unknownActionSurvivesEndedRecordRestartAndPlanContinuationButNotDifferentConversation() {
        val f = openProposal(); approve(f); begin("one", 1)
        val call = call("terminal", """{"command":"echo ok"}""", 3)
        assertEquals("ACTION_OUTCOME_UNKNOWN", code(gate.execute(call) {
            AgentModelClient.ToolResult("""{"ok":false,"code":"ACTION_OUTCOME_UNKNOWN"}""")
        }))
        PlanModeStore.end("run")
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        val restored = PlanModeStore.plans.value.single()
        assertFalse(restored.active); assertNull(restored.approvedRevision); assertEquals("partially_blocked", restored.phase)
        assertTrue(restored.interruptedWork); assertEquals(1, restored.uncertainActions.size)
        val step = PlanStep("one", "first", actions = listOf(PlanAction("terminal", """{"command":"echo ok"}""")))
        val next = PlanModeStore.propose("chat", "resume", "continued", "content", listOf(step), planLineageId = "run")
        assertEquals(restored.uncertainActions, next.uncertainActions)
        val unrelated = PlanModeStore.propose("other-chat", "unrelated", "different", "content", listOf(step), planLineageId = "run")
        assertTrue(unrelated.uncertainActions.isEmpty())
    }

    @Test fun actualContinuationGetsFreshGateAndCannotReplayUnknownActionAfterNewApproval() {
        val initial = openProposal(); approve(initial); begin("one", 1)
        val action = call("terminal", """{"command":"echo ok"}""", 3)
        gate.execute(action) { AgentModelClient.ToolResult("""{"ok":false,"code":"ACTION_OUTCOME_UNKNOWN"}""") }
        PlanModeStore.end("run")
        val original = io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunRequest("run", "start",
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList(),
            handoff = io.github.mangi.eta.agent.runtime.AgentRuntimeWire.EntryHandoff("run", "agent_ui",
                io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload("chat").toJson()), requestMode = "plan")
        val resumed = io.github.mangi.eta.agent.runtime.AgentContinuationBuilder.build(original,
            AgentModelClient.ModelResponse.Text("uncertain"), "continue", "run-next", 1L)
        assertEquals("plan", resumed.requestMode); assertEquals("run", resumed.planLineageId)
        val nextController = AgentRunController()
        val nextGate = PlanRunGate("chat", resumed.runId, nextController, resumed.planLineageId)
        PlanModeStore.listen(resumed.runId, nextController::signalWorkAvailable) { nextGate.mayAct() }
        assertEquals("PLAN_APPROVAL_REQUIRED", code(nextGate.execute(action) { error("old approval leaked") }))
        val future = executor.submit<AgentModelClient.ToolResult> { nextGate.execute(call(PlanRunGate.PROPOSE, proposal(), 1)) { error("delegate") } }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (PlanModeStore.current(resumed.runId) == null && System.nanoTime() < limit) Thread.sleep(2)
        val p = PlanModeStore.current(resumed.runId)!!
        assertTrue(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_APPROVED", code(future.get(2, TimeUnit.SECONDS)))
        assertEquals("PLAN_STEP_READY", code(nextGate.execute(call(PlanRunGate.BEGIN, """{"step_id":"one","revision":1}""", 2)) { error("delegate") }))
        assertEquals("PLAN_ACTION_ALREADY_ATTEMPTED", code(nextGate.execute(action) { error("unknown action was replayed") }))
        nextController.cancel(); PlanModeStore.end(resumed.runId)
    }
    @Test fun processDeathWithRunningReservationRestoresBlockedNotApproval() {
        val f = openProposal(); approve(f)
        val p = PlanModeStore.current("run")!!; val step = p.steps.first(); val action = step.actions.single()
        assertTrue(PlanModeStore.reserveAction("run", p.revision, step, action, action.fingerprint()) { true })
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        val recovered = PlanModeStore.plans.value.single()
        assertFalse(recovered.active); assertNull(recovered.approvedRevision); assertEquals("partially_blocked", recovered.phase)
        assertEquals("blocked", recovered.steps.first().status)
        assertTrue(recovered.interruptedWork); assertTrue(action.fingerprint() in recovered.uncertainActions)
    }

    @Test fun steeringDuringReservationStopsBeforeDispatchAndDoesNotMarkActionUnknown() {
        val f = openProposal(); approve(f); begin("one", 1)
        var injected = false
        PlanModeStore.listen("race", {
            if (!injected && PlanModeStore.current("run")?.steps?.first()?.status == "running") {
                injected = true; controller.steer("new direction")
            }
        })
        var calls = 0
        assertEquals("PLAN_REVISION_CHANGED", code(gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) { calls++; ok() }))
        assertEquals(0, calls)
        assertEquals("pending", PlanModeStore.current("run")!!.steps.first().status)
        assertTrue(PlanModeStore.current("run")!!.uncertainActions.isEmpty())
    }
    @Test fun parallelSameStepReservationsCanHaveOnlyOneWinner() {
        val f = openProposal(); approve(f)
        val p = PlanModeStore.current("run")!!; val step = p.steps.first(); val action = step.actions.single()
        val pool = Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2); val go = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = (1..2).map { pool.submit<Boolean> {
                ready.countDown(); check(go.await(2, TimeUnit.SECONDS))
                PlanModeStore.reserveAction("run", p.revision, step, action, action.fingerprint()) { true }
            } }
            assertTrue(ready.await(2, TimeUnit.SECONDS)); go.countDown()
            assertEquals(1, tasks.count { it.get(2, TimeUnit.SECONDS) })
            assertEquals("running", PlanModeStore.current("run")!!.steps.first().status)
            assertEquals(1, PlanModeStore.current("run")!!.uncertainActions.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun legacyEndedApprovedRecordCannotRestoreVisibleOrExecutableApproval() {
        val f = openProposal(); approve(f)
        val persisted = java.io.File(dir, "planmode/plans.json")
        val json = org.json.JSONArray(persisted.readText())
        json.getJSONObject(0).put("active", false).put("status", "approved").put("phase", "ended")
        persisted.writeText(json.toString())
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        val restored = PlanModeStore.plans.value.single()
        assertEquals("ended", restored.status); assertEquals("ended", restored.phase)
        assertFalse(restored.active); assertNull(restored.approvedRevision)
        assertFalse(PlanModeStore.approve(restored.planId, restored.revision))
    }

    @Test fun editIsNotDiscardedWhenSteeringArrivesBeforeBoundary() {
        val future = openProposal(); val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.edit(p.planId, p.revision, "edited content"))
        assertTrue(controller.steer("new steering"))
        val steering = controller.pollSteeringInput()!!
        gate.onSteering(steering.sequence)
        assertTrue(PlanModeStore.current("run")!!.editPending)
        assertTrue(gate.editedPlanMessage()!!.contains("edited content"))
        assertEquals("PLAN_CHANGED", code(future.get(2, TimeUnit.SECONDS)))
    }

    @Test fun previouslyApprovedIndependentActionSurvivesUnansweredQuestionsWithoutReapproval() {
        val first = openProposal(independent = true); approve(first)
        val revision = PlanModeStore.current("run")!!.revision
        assertTrue(PlanModeStore.current("run")!!.independenceApproved)
        gate.noteQuestion(4, """{"status":"unanswered"}""")
        assertEquals("PLAN_TURN_BOUNDARY", code(begin("one", revision, 4)))
        assertEquals("PLAN_STEP_READY", code(begin("one", revision, 5)))
        assertEquals("PLAN_QUESTION_BOUNDARY", code(attempt(round = 4)))
        var dispatched = 0
        assertTrue(JSONObject(gate.execute(call("terminal", """{"command":"echo ok"}""", 6)) {
            dispatched++; ok()
        }.content).getBoolean("ok"))
        assertEquals(1, dispatched)
        assertEquals(revision, PlanModeStore.current("run")!!.approvedRevision)
        gate.noteQuestion(7, """{"status":"unanswered"}""")
        assertTrue(PlanModeStore.current("run")!!.independenceApproved)
        assertEquals("PLAN_STEP_BLOCKED", code(begin("two", revision, 8)))
    }

    @Test fun independenceNeverAuthorizesChangedArgumentsOrRevisedPlan() {
        val f = openProposal(independent = true); approve(f)
        gate.noteQuestion(3, """{"status":"unanswered"}""")
        assertEquals("PLAN_STEP_READY", code(begin("one", 1, 4)))
        assertEquals("PLAN_ACTION_MISMATCH", code(attempt(args = """{"command":"different"}""", round = 5)))
        val p = PlanModeStore.current("run")!!
        assertTrue(PlanModeStore.edit(p.planId, p.revision, "新范围"))
        assertFalse(PlanModeStore.approve(p.planId, p.revision))
        assertEquals("PLAN_EDIT_PENDING", code(attempt(round = 6)))
        assertNull(PlanModeStore.current("run")!!.approvedRevision)
    }

    @Test fun failureToPersistUnansweredQuestionDoesNotPermitDependentAction() {
        val f = openProposal(); approve(f); begin("one", 1)
        PlanModeStore.failNextWriteForTest()
        assertTrue(runCatching { gate.noteQuestion(3, """{"status":"unanswered"}""") }.isFailure)
        assertFalse(PlanModeStore.current("run")!!.questionBlocked)
        var count = 0
        assertEquals("PLAN_STEP_BLOCKED", code(gate.execute(call("terminal", """{"command":"echo ok"}""", 4)) { count++; ok() }))
        assertEquals(0, count)
    }

    @Test fun phaseCompletesOnlyAfterAllActionsAndNormalRunEnd() {
        val f = openProposal(); approve(f); begin("one", 1)
        assertTrue(JSONObject(attempt().content).getBoolean("ok"))
        assertEquals("running", PlanModeStore.current("run")!!.phase)
        begin("two", 1)
        assertTrue(JSONObject(attempt("mcp_write", """{"value":1}""", 4).content).getBoolean("ok"))
        assertEquals("running", PlanModeStore.current("run")!!.phase)
        PlanModeStore.end("run", completedRun = true)
        val finished = PlanModeStore.plans.value.single()
        assertFalse(finished.active); assertNull(finished.approvedRevision)
        assertEquals("completed", finished.phase) // the action plan, not external validation
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        assertEquals("completed", PlanModeStore.plans.value.single().phase)
    }

    @Test fun phaseRemainsBlockedAfterFailedActionAndRestart() {
        val f = openProposal(); approve(f); begin("one", 1)
        gate.execute(call("terminal", """{"command":"echo ok"}""", 3)) {
            AgentModelClient.ToolResult("""{"ok":false,"code":"ACTION_OUTCOME_UNKNOWN"}""")
        }
        assertEquals("partially_blocked", PlanModeStore.current("run")!!.phase)
        PlanModeStore.end("run", completedRun = true)
        assertEquals("partially_blocked", PlanModeStore.plans.value.single().phase)
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(dir)
        assertEquals("partially_blocked", PlanModeStore.plans.value.single().phase)
    }

}
