package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeStopBoundaryTest {
    @Test fun stopBeforeCommitPersistsAndPublishesOnlyStoppedResultAfterCleanup() {
        val published = mutableListOf<AgentRuntimeWire.RunResult>()
        val persisted = mutableListOf<AgentRuntimeWire.RunResult>()
        val released = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession("stop", resultSink = published::add, beforeTerminalPublish = released::add)
        val transcript = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage("assistant", "partial"))
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(operationId = "stop", messages = emptyList())
        assertTrue(session.requestStop())
        assertTrue(session.controller.isCancelled)
        assertFalse(session.isTerminal)
        assertTrue(published.isEmpty())
        assertFalse(session.requestStop())
        assertTrue(session.complete(AgentRuntimeWire.RunResult("stop", true, "stale success",
            transcript = transcript, contextSnapshot = snapshot)) { persisted += it })
        val result = published.single()
        assertFalse(result.ok); assertEquals("", result.content); assertEquals("已停止", result.error)
        assertEquals(transcript, result.transcript); assertEquals(snapshot, result.contextSnapshot)
        assertEquals(listOf(result), persisted); assertEquals(listOf(result), released)
        assertTrue(session.isTerminal)
        assertFalse(session.complete(AgentRuntimeWire.RunResult("stop", true, "duplicate")))
    }

    @Test fun rawControllerCancellationBeforeCommitCannotPersistSuccess() {
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession("raw", resultSink = results::add)
        session.controller.cancel()
        var persisted: AgentRuntimeWire.RunResult? = null
        assertTrue(session.complete(AgentRuntimeWire.RunResult("raw", true, "stale")) { persisted = it })
        assertFalse(results.single().ok); assertEquals("已停止", results.single().error)
        assertEquals(results.single(), persisted)
    }

    @Test fun completionClaimBeforeStopKeepsSuccessAndDoesNotCancelResources() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
        val session = AgentRuntimeSession("done", resultSink = results::add)
        val completed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { completed.set(session.complete(AgentRuntimeWire.RunResult("done", true, "done")) {
                assertTrue(it.ok); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }) } catch (t: Throwable) { failure.set(t) }
        }
        try {
            worker.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(session.isCommitting)
            assertFalse(session.requestStop()); assertFalse(session.cancel("late"))
            assertFalse(session.controller.isCancelled)
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertNull(failure.get()); assertTrue(completed.get())
            assertEquals(1, results.size); assertTrue(results.single().ok)
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun stopClaimWinsEvenWhileItsResourceCallbacksHaveNotReturned() {
        val inCancellation = CountDownLatch(1); val release = CountDownLatch(1)
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession("callback", resultSink = results::add)
        val stop = AtomicBoolean()
        val binding = session.controller.register { inCancellation.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val stopper = Thread { stop.set(session.requestStop()) }
        try {
            stopper.start(); assertTrue(inCancellation.await(3, TimeUnit.SECONDS))
            assertFalse(session.isCommitting) // callback is not holding the session lock
            assertTrue(session.complete(AgentRuntimeWire.RunResult("callback", true, "stale")) { assertFalse(it.ok) })
            assertFalse(results.single().ok)
            release.countDown(); stopper.join(5000)
            assertFalse(stopper.isAlive); assertTrue(stop.get())
        } finally { release.countDown(); stopper.join(5000); binding.close() }
    }

    @Test fun stopRejectsRunFinishedButStillAllowsPartialCheckpointUntilDrain() {
        val events = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession("event", eventSink = events::add)
        assertTrue(session.requestStop())
        val partial = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage("assistant", "partial"))
        session.commitTranscript(partial) {}
        assertEquals(partial, session.transcript)
        assertThrows(AgentRunCancelledException::class.java) { session.emit(AgentEvent.RunFinished(1, 7)) }
        assertTrue(events.isEmpty())
    }

    @Test fun stopPreservesSpecificFailureAndInterruptedRecoveryMessage() {
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession("failed", resultSink = results::add)
        assertTrue(session.requestStop())
        val result = AgentRuntimeWire.RunResult("failed", false, "", "已停止；子代理恢复记录读取失败")
        assertTrue(session.complete(result) { assertEquals(result, it) })
        assertEquals(result, results.single())
    }

    @Test fun racingStopAndCompletionPublishAndPersistTheSameSingleWinner() {
        repeat(50) { index ->
            val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
            val persisted = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
            val session = AgentRuntimeSession("race-$index", resultSink = results::add)
            val start = CountDownLatch(1); val stopped = AtomicBoolean()
            val failure = AtomicReference<Throwable?>()
            val completion = Thread {
                try { start.await(); assertTrue(session.complete(AgentRuntimeWire.RunResult(session.runId, true, "done")) { persisted += it }) }
                catch (t: Throwable) { failure.set(t) }
            }
            val stop = Thread { try { start.await(); stopped.set(session.requestStop()) } catch (t: Throwable) { failure.set(t) } }
            completion.start(); stop.start(); start.countDown(); completion.join(5000); stop.join(5000)
            assertFalse(completion.isAlive); assertFalse(stop.isAlive); assertNull(failure.get())
            assertEquals(1, results.size); assertEquals(results, persisted)
            assertEquals(!stopped.get(), results.single().ok)
            assertEquals(stopped.get(), session.controller.isCancelled)
        }
    }
}
