package io.github.mangi.eta.ui.app

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real UI state against Service/Registry/Client Messenger, without model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PowerRunConversationIntegrationTest {
    @Before fun reset() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun runningInputReplayAndLiveEventsUseOneSubscriptionAndNeverStart() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("power-live", "power-key")
            val run = fixture.admit(request)
            run.prepared(request)
            run.session.emit(delta("replay"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                fixture.await { app.conversationPaneState.tasksConnected && fixture.messages.contains(AgentRuntimeWire.MSG_ATTACH_RUN) }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                fixture.await { app.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "replay" } }
                val attaches = fixture.messages.count { it == AgentRuntimeWire.MSG_ATTACH_RUN }
                repeat(3) { assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) }) }
                assertEquals(attaches, fixture.messages.count { it == AgentRuntimeWire.MSG_ATTACH_RUN })
                assertEquals(1, app.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals("fixture task", app.homeState.messages.filterIsInstance<UserMessageUi>().single().content)
                run.session.emit(delta(" live"))
                fixture.await { app.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "replay live" } }
                assertTrue(app.homeState.isStreaming)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "replay live"))
                fixture.await { !app.homeState.isStreaming }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertEquals(1, app.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, app.homeState.messages.filterIsInstance<AgentMessageUi>().count { it.content == "replay live" })
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun deferredPreparingBecomesRealInputWithoutSecondObserverOrFakeUserText() {
        RuntimeMessengerFixture().use { fixture ->
            val header = fixture.request("power-deferred", "deferred-key")
            val run = fixture.admit(header)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertTrue(app.homeState.isStreaming)
                fixture.await { app.selectedRunStatus != null }
                assertNotNull(app.selectedRunStatus)
                assertTrue(app.homeState.messages.filterIsInstance<UserMessageUi>().isEmpty())
                val ready = header.copy(prompt = "actual deferred body", handoff = header.handoff!!.copy(
                    payload = AgentExternalArchivePayload("actual deferred body", "deferred-key", "Ready").toJson()))
                run.prepared(ready)
                publish(fixture)
                fixture.await { app.homeState.messages.filterIsInstance<UserMessageUi>().singleOrNull()?.content == "actual deferred body" }
                assertNull(app.selectedRunStatus)
                assertEquals(1, fixture.messages.count { it == AgentRuntimeWire.MSG_ATTACH_RUN })
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "done"))
                fixture.await { !app.homeState.isStreaming }
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun backgroundSnapshotsDoNotSelectAndStopTargetsSelectedPowerRunOnly() {
        RuntimeMessengerFixture().use { fixture ->
            val aRequest = fixture.request("long-a", "a-key")
            val a = fixture.admit(aRequest).also { it.prepared(aRequest) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(a.runId) })
                val aId = app.conversationPaneState.selectedConversationId
                val bRequest = fixture.request("power-b", "b-key")
                val b = fixture.admit(bRequest).also { it.prepared(bRequest) }
                publish(fixture)
                fixture.await { app.activeTask(b.runId) != null }
                assertEquals(aId, app.conversationPaneState.selectedConversationId)
                assertTrue(ui(fixture, scope) { app.openRunConversation(b.runId) })
                assertEquals(AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "b-key"),
                    app.conversationPaneState.selectedConversationId)
                app.stopCurrentRun()
                fixture.await { b.session.controller.isCancelled }
                assertFalse(a.session.controller.isCancelled)
                fixture.complete(b, AgentRuntimeWire.RunResult(b.runId, false, "", "已停止"))
                fixture.await { !app.homeState.isStreaming }
                assertTrue(ui(fixture, scope) { app.openRunConversation(b.runId) })
                assertEquals(AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "b-key"),
                    app.conversationPaneState.selectedConversationId)
                fixture.complete(a, AgentRuntimeWire.RunResult(a.runId, true, "long done"))
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun scopeRecreationAndOldTerminalEntryRestoreSameConversationWithoutDuplicating() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("recreated-power", "stable-key")
            val run = fixture.admit(request).also { it.prepared(request) }
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                val id = app.conversationPaneState.selectedConversationId
                scope.cancel()
                fixture.await { AgentRuntimeWire.MSG_DETACH_RUN in fixture.messages }
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val restored = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { restored.openRunConversation(run.runId) })
                assertEquals(id, restored.conversationPaneState.selectedConversationId)
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "restored answer"))
                fixture.await { !restored.homeState.isStreaming && AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages }
                scope.cancel()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val terminal = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { terminal.openRunConversation(run.runId) })
                assertEquals(id, terminal.conversationPaneState.selectedConversationId)
                assertEquals(1, terminal.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, terminal.homeState.messages.filterIsInstance<AgentMessageUi>().count { it.content == "restored answer" })
                assertFalse(ui(fixture, scope) { terminal.openRunConversation("unknown-run") })
                assertEquals(id, terminal.conversationPaneState.selectedConversationId)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun liveResultCannotAckBeforeSuccessfulConversationCommitAndArchiveRetryIsIdempotent() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("live-save-failure", "durable-key")
            val run = fixture.admit(request).also { it.prepared(request) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sql = EtaDatabase.get(fixture.context).openHelper.writableDatabase
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                sql.execSQL("CREATE TRIGGER reject_live_result BEFORE INSERT ON conversation_messages " +
                    "WHEN NEW.type = 'assistant' BEGIN SELECT RAISE(ABORT, 'fixture persistence failure'); END")
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "durable answer"))
                fixture.await { !app.homeState.isStreaming }
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_ACK_RESULT))
                assertTrue(AgentRuntimeResultStore.list(fixture.context).any { it.result.runId == run.runId })
                assertTrue(AgentRunArchiveStore.list(fixture.context).any { it.result.runId == run.runId })
                sql.execSQL("DROP TRIGGER reject_live_result")
                app.refreshRuntimeResults()
                fixture.await { AgentRunArchiveStore.list(fixture.context).isEmpty() }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertEquals(1, app.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, app.homeState.messages.filterIsInstance<AgentMessageUi>().count { it.content == "durable answer" })
                assertTrue(fixture.messages.contains(AgentRuntimeWire.MSG_ACK_RESULT))
            } finally {
                sql.execSQL("DROP TRIGGER IF EXISTS reject_live_result")
                scope.cancel()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun sameKeyNextExternalTurnReusesConversationAndRuntimeKeepsWriterOwnership() {
        RuntimeMessengerFixture().use { fixture ->
            val firstRequest = fixture.request("first-power", "one-key")
            val first = fixture.admit(firstRequest).also { it.prepared(firstRequest) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(first.runId) })
                val id = app.conversationPaneState.selectedConversationId
                app.sendCurrentMessage("queued while power is active")
                assertEquals(1, app.homeState.queuedMessages.size)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
                // Remove the queued draft before completing so this fixture never contacts a model.
                app.recallQueuedMessage(app.homeState.queuedMessages.single().id, "")
                fixture.complete(first, AgentRuntimeWire.RunResult(first.runId, true, "first answer"))
                fixture.await { !app.homeState.isStreaming }
                val secondRequest = fixture.request("second-power", "one-key")
                val second = fixture.admit(secondRequest).also { it.prepared(secondRequest) }
                publish(fixture)
                assertTrue(ui(fixture, scope) { app.openRunConversation(second.runId) })
                assertEquals(id, app.conversationPaneState.selectedConversationId)
                assertEquals("queued while power is active", app.homeState.input)
                assertEquals(2, app.homeState.messages.filterIsInstance<UserMessageUi>().size)
                fixture.complete(second, AgentRuntimeWire.RunResult(second.runId, true, "second answer"))
                fixture.await { !app.homeState.isStreaming }
                assertTrue(ui(fixture, scope) { app.openRunConversation(first.runId) })
                assertEquals(id, app.conversationPaneState.selectedConversationId)
                assertEquals(listOf("first answer", "second answer"),
                    app.homeState.messages.filterIsInstance<AgentMessageUi>().map { it.content })
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun runningImagePreviewComesFromActualRequestAndSurvivesTerminalImport() {
        RuntimeMessengerFixture().use { fixture ->
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.GREEN)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
            val image = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            val request = fixture.request("image-power", "image-key").copy(images = listOf(
                AgentModelClient.ModelImage(image, "image/png", bytes.size, source = "user_attach")))
            val run = fixture.admit(request).also { it.prepared(request) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                val previews = app.homeState.messages.filterIsInstance<UserMessageUi>().single().images
                assertEquals(1, previews.size)
                assertTrue(previews.single().startsWith("data:image/"))
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "image answer"))
                fixture.await { !app.homeState.isStreaming }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertEquals(previews, app.homeState.messages.filterIsInstance<UserMessageUi>().single().images)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun terminalPresentationPlaceholderNeverCreatesAnActiveObserverOrTask() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("already-terminal", "terminal-key")
            val run = fixture.admit(request).also { it.prepared(request) }
            fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "already done"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                fixture.await { !app.homeState.isStreaming }
                assertNull(app.activeTask(run.runId))
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_ATTACH_RUN))
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
                assertEquals("already done", app.homeState.messages.filterIsInstance<AgentMessageUi>().single().content)
                // Both runtime stores are now gone; a run-only old click still opens persisted identity.
                fixture.await { AgentRunArchiveStore.list(fixture.context).isEmpty() &&
                    AgentRuntimeResultStore.list(fixture.context).isEmpty() }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertEquals(AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                    "terminal-key"), app.conversationPaneState.selectedConversationId)
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    private fun delta(text: String) = AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, text.length, text)

    private fun publish(fixture: RuntimeMessengerFixture) {
        AgentRuntimeService::class.java.getDeclaredMethod("publishActiveRuns", List::class.java).apply { isAccessible = true }
            .invoke(fixture.service, fixture.registry.snapshots())
    }

    private fun <T> ui(fixture: RuntimeMessengerFixture, scope: CoroutineScope, action: suspend () -> T): T {
        val result = scope.async { action() }
        fixture.await { result.isCompleted }
        return runBlocking { result.await() }
    }
}
