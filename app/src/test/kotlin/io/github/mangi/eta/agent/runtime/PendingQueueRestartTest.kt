package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PendingQueueRestartTest {
    @Before fun reset() { EtaDatabase.closeForTests(); RuntimeEnvironment.getApplication().deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }

    private fun seed(f: RuntimeMessengerFixture, id: String, queue: List<QueuedMessageUi>) = runBlocking {
        val state = AgentChatHomeUiState(messages = listOf(UserMessageUi("user-seed", "task")),
            input = "", isStreaming = false, thinkingEnabled = false, queuedMessages = queue)
        AgentConversationStore.save(f.context, id, mapOf(id to state), mapOf(id to "task"), mapOf(id to 1L))
    }

    @Test fun restartKeepsQueuedInputsAndDoesNotStartANewRuntime() {
        RuntimeMessengerFixture().use { f ->
            seed(f, "saved", listOf(QueuedMessageUi("a", "first"), QueuedMessageUi("b", "plan", requestMode = "plan")))
            EtaDatabase.closeForTests()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(f.context, scope)
                f.await { app.conversationPaneState.tasksConnected }
                assertEquals(listOf("a", "b"), app.homeState.queuedMessages.map { it.id })
                assertEquals("plan", app.homeState.queuedMessages.last().requestMode)
                assertTrue(app.homeState.queuedMessages.all { it.restoredFromStorage })
                assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
                assertFalse(AgentRuntimeWire.MSG_STEER_RUN in f.messages)
                app.removeQueuedMessage("a")
                f.await { AgentConversationStore.load(f.context).conversationsById["saved"]!!.queuedMessages.size == 1 }
            } finally { scope.cancel(); f.await { scope.coroutineContext[Job]!!.isCompleted }; shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun acceptedBeforeUiDeathIsReconciledAndNeverSubmittedTwice() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("accepted-restart")); run.prepared(run.request)
            seed(f, run.conversationId, listOf(QueuedMessageUi("q", "guidance", steeringRunId = run.runId)))
            assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "guidance") }))
            EtaDatabase.closeForTests()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(f.context, scope)
                f.await { app.homeState.queuedMessages.isEmpty() && app.homeState.messages.any { it.id == "user-${run.runId}-supplement-1" } }
                assertEquals(1, app.homeState.messages.count { it.id == "user-${run.runId}-supplement-1" })
                assertEquals(1, f.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })
                assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
                assertEquals("guidance", run.session.controller.pollSteeringMessage())
                assertNull(run.session.controller.pollSteeringInput())
            } finally { scope.cancel(); f.await { scope.coroutineContext[Job]!!.isCompleted }; shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun notDeliveredBeforeUiDeathBecomesActionableOnlyAfterRuntimeQuery() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("missing-restart")); run.prepared(run.request)
            seed(f, run.conversationId, listOf(QueuedMessageUi("q", "not delivered", steeringRunId = run.runId)))
            EtaDatabase.closeForTests()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(f.context, scope)
                assertTrue(app.homeState.queuedMessages.single().steeringUnconfirmed)
                f.await { app.homeState.queuedMessages.singleOrNull()?.steeringUnconfirmed == false }
                val kept = app.homeState.queuedMessages.single()
                assertEquals("q", kept.id)
                assertEquals("", kept.steeringRunId)
                assertTrue(kept.restoredFromStorage)
                assertFalse(AgentRuntimeWire.MSG_STEER_RUN in f.messages)
                assertNull(run.session.controller.pollSteeringInput())
            } finally { scope.cancel(); f.await { scope.coroutineContext[Job]!!.isCompleted }; shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun unavailableRuntimeKeepsTheRestoredInputUnconfirmedAndProtected() {
        RuntimeMessengerFixture().use { f ->
            seed(f, "unknown", listOf(QueuedMessageUi("q", "unknown delivery", steeringRunId = "absent-run")))
            EtaDatabase.closeForTests()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(f.context, scope)
                f.await { AgentRuntimeWire.MSG_QUERY_STEERING in f.messages }
                assertTrue(app.homeState.queuedMessages.single().steeringUnconfirmed)
                app.removeQueuedMessage("q"); app.recallQueuedMessage("q", ""); app.steerQueuedMessage("q")
                assertEquals("unknown delivery", app.homeState.queuedMessages.single().content)
                assertFalse(AgentRuntimeWire.MSG_STEER_RUN in f.messages)
                assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
            } finally { scope.cancel(); f.await { scope.coroutineContext[Job]!!.isCompleted }; shadowOf(Looper.getMainLooper()).idle() }
        }
    }
}
