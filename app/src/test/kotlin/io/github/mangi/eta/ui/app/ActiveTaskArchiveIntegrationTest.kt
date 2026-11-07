package io.github.mangi.eta.ui.app

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
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
class ActiveTaskArchiveIntegrationTest {
    @Before fun reset() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun failedConversationCommitNeverAcknowledgesAndRetryImportsDurablyOnce() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("save-retry", "save-retry-key")
            val run = fixture.admit(request)
            run.prepared(request)
            fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "retry answer"))
            val sql = EtaDatabase.get(fixture.context).openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_external_import BEFORE INSERT ON conversation_messages " +
                "WHEN NEW.id = 'user-save-retry' BEGIN SELECT RAISE(ABORT, 'fixture persistence failure'); END")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                val recovering = AgentAppState::class.java.getDeclaredField("runtimeRecoveryInProgress")
                    .apply { isAccessible = true }.get(app) as java.util.concurrent.atomic.AtomicBoolean
                fixture.await { app.conversationPaneState.tasksConnected && !recovering.get() }
                assertFalse(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
                assertEquals(run.runId, AgentRuntimeResultStore.list(fixture.context).single().result.runId)
                assertEquals(run.runId, AgentRunArchiveStore.list(fixture.context).single().result.runId)
                sql.execSQL("DROP TRIGGER reject_external_import")
                app.refreshRuntimeResults()
                fixture.await { AgentRunArchiveStore.list(fixture.context).isEmpty() && !recovering.get() }
                val id = AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "save-retry-key")
                val restored = requireNotNull(fixture.result(fixture.worker { runBlocking {
                    AgentConversationStore.loadConversation(fixture.context, id)
                } }))
                assertEquals(1, restored.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, restored.messages.filterIsInstance<AgentMessageUi>().count { it.content == "retry answer" })
                assertTrue(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
            } finally {
                scope.cancel()
                sql.execSQL("DROP TRIGGER IF EXISTS reject_external_import")
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun initialEmptySnapshotImportsFastTerminalOnceAndReconnectDoesNotDuplicateHistory() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("fast-terminal", "stable-key")
            val run = fixture.admit(request)
            run.prepared(request)
            fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "answer"))
            // Simulate outbox succeeded but the separate archive write failed.
            AgentRunArchiveStore.remove(fixture.context, run.runId)
            assertTrue(fixture.registry.snapshots().isEmpty())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                fixture.await { app.conversationPaneState.tasksConnected && app.conversationPaneState.conversations.isNotEmpty() }
                val imported = fixture.result(fixture.worker { AgentConversationStore.load(fixture.context) })
                val id = AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "stable-key")
                assertTrue(id in imported.conversationsById)
                // Re-enqueue the same durable archive, just as a process reconnect can expose it again.
                AgentRunArchiveStore.add(fixture.context, AgentRunArchiveStore.ArchivedRun(request.handoff!!,
                    emptyList(), AgentRuntimeWire.RunResult(run.runId, true, "answer"), 1L))
                app.refreshRuntimeResults()
                fixture.await { AgentRunArchiveStore.list(fixture.context).isEmpty() }
                val saved = fixture.result(fixture.worker { runBlocking { AgentConversationStore.loadConversation(fixture.context, id) } })
                val messages = requireNotNull(saved).messages
                assertEquals(1, messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, messages.filterIsInstance<AgentMessageUi>().count { it.content == "answer" })
                assertTrue(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun messengerSaturationKeepsTerminalRevisionEvenWhenCollectorOnlySeesLastEmptySnapshot() {
        RuntimeMessengerFixture().use { fixture ->
            // Establish the real fixture binding through its worker/looper pump before testing
            // channel saturation, rather than racing lazy callbackFlow startup with teardown.
            val initial = fixture.result(fixture.worker { fixture.client.queryActiveRuns() })
            assertTrue(initial is AgentRuntimeClient.ActiveRunsQuery.Known)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val updates = Channel<AgentRuntimeClient.ActiveRunsUpdate>(Channel.CONFLATED)
            try {
                val collector = scope.launch { fixture.client.observeActiveRuns().collect { updates.send(it) } }
                fixture.await { AgentRuntimeWire.MSG_SUBSCRIBE_ACTIVE_RUNS in fixture.messages }
                val publish = AgentRuntimeService::class.java.getDeclaredMethod("publishActiveRuns", List::class.java)
                    .apply { isAccessible = true }
                repeat(200) { index ->
                    publish.invoke(fixture.service, listOf(AgentRuntimeWire.ActiveRunSnapshot("short-$index", "conversation")))
                }
                publish.invoke(fixture.service, emptyList<AgentRuntimeWire.ActiveRunSnapshot>())
                shadowOf(Looper.getMainLooper()).idle()
                val last = requireNotNull(updates.tryReceive().getOrNull())
                assertTrue(last.snapshots.isEmpty())
                assertEquals(200L, last.terminalRevision)
                collector.cancel()
                fixture.await { AgentRuntimeWire.MSG_UNSUBSCRIBE_ACTIVE_RUNS in fixture.messages }
                // A new subscription must receive a fresh authoritative empty snapshot, not an old run.
                val second = scope.launch { fixture.client.observeActiveRuns().collect { updates.send(it) } }
                fixture.await { fixture.messages.count { it == AgentRuntimeWire.MSG_SUBSCRIBE_ACTIVE_RUNS } == 2 }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(requireNotNull(updates.tryReceive().getOrNull()).snapshots.isEmpty())
                second.cancel()
            } finally { scope.cancel(); updates.close(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }
}
