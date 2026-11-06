package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanCancellationCommitTest {
    private lateinit var directory: java.io.File
    private val run = "plan-terminal-race"

    @Before fun setup() {
        directory = Files.createTempDirectory("plan-cancel-commit-").toFile()
        PlanModeStore.resetForTest(); PlanModeStore.initializeAt(directory)
        PlanModeStore.listen(run, {})
        val action = PlanAction("terminal", "{}")
        val pending = PlanStep("work", "Work", actions = listOf(action))
        val plan = PlanModeStore.propose("chat", run, "Approved work", "Complete one exact action", listOf(pending))
        assertTrue(PlanModeStore.approve(plan.planId, plan.revision))
        assertTrue(PlanModeStore.reserveAction(run, plan.revision, pending, action, action.fingerprint()) { true })
        assertTrue(PlanModeStore.finishAction(run, plan.revision, pending.id, 0, action.fingerprint(), true))
        assertEquals("completed", PlanModeStore.current(run)!!.steps.single().status)
    }

    @After fun cleanup() {
        PlanModeStore.resetForTest(); directory.deleteRecursively()
    }

    private fun endPlan(result: AgentRuntimeWire.RunResult) {
        PlanModeStore.end(run, completedRun = result.ok)
        PlanModeStore.unlisten(run)
    }

    @Test fun stopAfterModelAnswerBeforeCommitCannotMarkEvenFinishedActionsAsSuccessfulRun() {
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(run, resultSink = results::add)
        assertTrue(session.requestStop())
        assertTrue(session.complete(AgentRuntimeWire.RunResult(run, true, "model answer"), ::endPlan))
        assertFalse(results.single().ok)
        val ended = PlanModeStore.plans.value.single()
        assertFalse(ended.active); assertEquals("partially_blocked", ended.phase)
        assertTrue(ended.interruptedWork)
        assertEquals("completed", ended.steps.single().status) // completed side effects are not replayed
        assertNull(PlanModeStore.current(run))
    }

    @Test fun completionClaimBeforeLateStopKeepsPlanAndPublishedResultConsistent() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(run, resultSink = results::add)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { session.complete(AgentRuntimeWire.RunResult(run, true, "model answer")) { result ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); endPlan(result)
            } } catch (t: Throwable) { failure.set(t) }
        }
        try {
            worker.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(session.requestStop())
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertNull(failure.get()); assertTrue(results.single().ok)
            val ended = PlanModeStore.plans.value.single()
            assertFalse(ended.active); assertEquals("completed", ended.phase); assertFalse(ended.interruptedWork)
        } finally { release.countDown(); worker.join(5000) }
    }
}
