package io.github.mangi.eta.local.guipresentation

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiVisibilityGateTest {
    @Test
    fun actionWaitsForActualVisibilityAcknowledgmentNotPresentationDispatch() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gate = gate(queue)
        val frames = LinkedBlockingQueue<GuiVisibilityGate.Attempt>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<ToolExecutionDecision> {
                gate.await(AgentRunController(), { true }, { frames.put(it) })
            }
            queue.next().invoke()
            val frame = frames.poll(1, TimeUnit.SECONDS)!!
            assertFalse(result.isDone)
            frame.complete(ToolExecutionDecision.Allow)
            assertEquals(ToolExecutionDecision.Allow, result.get(1, TimeUnit.SECONDS))
        } finally { gate.close(); worker.shutdownNow() }
    }

    @Test
    fun addViewOrPermissionFailureIsAnExplicitRejection() {
        listOf("GUI_PRESENTATION_FAILED", "GUI_OVERLAY_PERMISSION_REQUIRED").forEach { code ->
            val gate = GuiVisibilityGate({ false }, { it() })
            val result = gate.await(AgentRunController(), { true }, {
                it.reject(ToolExecutionDecision.Reject(code, "not performed"))
            })
            assertEquals(code, (result as ToolExecutionDecision.Reject).code)
        }
    }

    @Test
    fun timeoutInvalidatesQueuedPresentationAndCannotReviveOldWindows() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gate = gate(queue, 10)
        val presentations = AtomicInteger()
        val result = gate.await(AgentRunController(), { true }, { presentations.incrementAndGet() })
        assertEquals("GUI_VISIBILITY_TIMEOUT", (result as ToolExecutionDecision.Reject).code)
        queue.next().invoke()
        assertEquals(0, presentations.get())
    }

    @Test
    fun lateVisibilityAcknowledgmentAfterTimeoutCannotBecomeAllow() {
        val frames = LinkedBlockingQueue<GuiVisibilityGate.Attempt>()
        val clock = AtomicLong()
        val gate = GuiVisibilityGate({ false }, { it() }, timeoutMs = 1, nanoTime = clock::get)
        val result = gate.await(AgentRunController(), { true }, {
            frames.put(it)
            clock.set(TimeUnit.MILLISECONDS.toNanos(2))
        })
        val attempt = frames.poll(1, TimeUnit.SECONDS)!!
        assertEquals("GUI_VISIBILITY_TIMEOUT", (result as ToolExecutionDecision.Reject).code)
        assertFalse(attempt.canPresent())
        assertFalse(attempt.complete(ToolExecutionDecision.Allow))
    }

    @Test
    fun cancellationUnblocksWithoutWaitingForMainAndQueuedWorkBecomesInert() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gate = gate(queue)
        val controller = AgentRunController()
        val presentations = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<ToolExecutionDecision> {
                gate.await(controller, { !controller.isCancelled }, { presentations.incrementAndGet() })
            }
            val posted = queue.next()
            controller.cancel()
            try { result.get(1, TimeUnit.SECONDS); error("cancellation must propagate") }
            catch (failure: ExecutionException) { assertTrue(failure.cause is AgentRunCancelledException) }
            posted()
            assertEquals(0, presentations.get())
        } finally { gate.close(); worker.shutdownNow() }
    }

    @Test
    fun ownerChangedWhileQueuedOrBeforeAcknowledgmentNeverAllowsAction() {
        listOf(false, true).forEach { presentBeforeChange ->
            val queue = LinkedBlockingQueue<() -> Unit>()
            val gate = gate(queue)
            val current = AtomicBoolean(true)
            val frames = LinkedBlockingQueue<GuiVisibilityGate.Attempt>()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<ToolExecutionDecision> {
                    gate.await(AgentRunController(), current::get, { frames.put(it) })
                }
                val posted = queue.next()
                if (presentBeforeChange) posted()
                current.set(false)
                if (presentBeforeChange) frames.poll(1, TimeUnit.SECONDS)!!.complete(ToolExecutionDecision.Allow)
                else posted()
                assertEquals("GUI_PRESENTATION_STALE", (result.get(1, TimeUnit.SECONDS) as ToolExecutionDecision.Reject).code)
            } finally { gate.close(); worker.shutdownNow() }
        }
    }

    @Test
    fun serviceCloseUnblocksPendingRequestsAndMainThreadNeverWaitsOnItself() {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gate = gate(queue)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<ToolExecutionDecision> {
                gate.await(AgentRunController(), { true }, { error("closed request must not present") })
            }
            val posted = queue.next()
            gate.close()
            assertEquals("GUI_PRESENTATION_STALE", (result.get(1, TimeUnit.SECONDS) as ToolExecutionDecision.Reject).code)
            posted()
        } finally { gate.close(); worker.shutdownNow() }
        val mainGate = GuiVisibilityGate({ true }, { error("main thread cannot dispatch then wait") })
        assertEquals("GUI_VISIBILITY_MAIN_THREAD", (mainGate.await(AgentRunController(), { true }, {})
            as ToolExecutionDecision.Reject).code)
    }

    @Test
    fun pauseDuringVisibilityWaitReturnsStaleSoTheCurrentActionCanDrain() {
        val controller = AgentRunController()
        val gate = GuiVisibilityGate({ false }, { it() })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<ToolExecutionDecision> {
                gate.await(controller, { !controller.isPaused }, {
                    controller.pause()
                    it.complete(ToolExecutionDecision.Allow)
                })
            }
            assertEquals("GUI_PRESENTATION_STALE", (result.get(1, TimeUnit.SECONDS) as ToolExecutionDecision.Reject).code)
        } finally { controller.resume(); gate.close(); worker.shutdownNow() }
    }

    private fun gate(queue: LinkedBlockingQueue<() -> Unit>, timeoutMs: Long = 1_000L) =
        GuiVisibilityGate({ false }, { queue.put(it) }, timeoutMs)

    private fun LinkedBlockingQueue<() -> Unit>.next(): () -> Unit =
        poll(1, TimeUnit.SECONDS) ?: error("presentation was not queued")
}
