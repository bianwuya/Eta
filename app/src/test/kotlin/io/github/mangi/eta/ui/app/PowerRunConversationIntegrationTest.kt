package io.github.mangi.eta.ui.app

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.agent.media.AgentImageCodec
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
import org.robolectric.annotation.GraphicsMode

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
                fixture.await {
                    // Wall-clock sleeps in the Messenger fixture do not advance the paused
                    // main Looper clock used by the production 80ms delta coalescer.
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                    app.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "replay live" }
                }
                assertTrue(app.homeState.isStreaming)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "replay live"))
                fixture.await { !app.homeState.isStreaming }
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                assertEquals(1, app.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, app.homeState.messages.filterIsInstance<AgentMessageUi>().count { it.content == "replay live" })
            } finally { cancelAndAwait(fixture, scope) }
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
            } finally { cancelAndAwait(fixture, scope) }
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
            } finally { cancelAndAwait(fixture, scope) }
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
                cancelAndAwait(fixture, scope)
                fixture.await { AgentRuntimeWire.MSG_DETACH_RUN in fixture.messages }
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val restored = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { restored.openRunConversation(run.runId) })
                assertEquals(id, restored.conversationPaneState.selectedConversationId)
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "restored answer"))
                fixture.await { !restored.homeState.isStreaming && AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages }
                cancelAndAwait(fixture, scope)
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val terminal = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { terminal.openRunConversation(run.runId) })
                assertEquals(id, terminal.conversationPaneState.selectedConversationId)
                assertEquals(1, terminal.homeState.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, terminal.homeState.messages.filterIsInstance<AgentMessageUi>().count { it.content == "restored answer" })
                assertFalse(ui(fixture, scope) { terminal.openRunConversation("unknown-run") })
                assertEquals(id, terminal.conversationPaneState.selectedConversationId)
                assertFalse(fixture.messages.contains(AgentRuntimeWire.MSG_START_RUN))
            } finally { cancelAndAwait(fixture, scope) }
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
                cancelAndAwait(fixture, scope)
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
            } finally { cancelAndAwait(fixture, scope) }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun runningImagePreviewComesFromActualRequestAndSurvivesTerminalImport() {
        RuntimeMessengerFixture().use { fixture ->
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.GREEN)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                bitmap.recycle()
                output.toByteArray()
            }
            // The real request ingestion produces a decoded attachment with dimensions;
            // previewFromReference intentionally cannot preview a dimensionless ModelImage.
            val image = AgentImageCodec.fromAttachmentBytes(bytes, source = "user_attach", mimeHint = "image/png")
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            val request = fixture.request("image-power", "image-key").copy(images = listOf(image))
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
            } finally { cancelAndAwait(fixture, scope) }
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
            } finally { cancelAndAwait(fixture, scope) }
        }
    }

    @Test fun newLocalWriterDuringSettlementQueryKeepsQueuedMessageUntouched() {
        RuntimeMessengerFixture().use { fixture ->
            configureModel(fixture)
            val request = fixture.request("queue-race", "queue-race-key")
            val run = fixture.admit(request).also { it.prepared(request) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                app.sendCurrentMessage("Q")
                fixture.await { !isRecovering(app) }
                recoveryFlag(app).set(true)
                val beforeSettlement = scope.coroutineContext[Job]!!.children.toSet()
                var settlementJobs = emptyList<Job>()
                val queuedId = app.homeState.queuedMessages.single().id
                var interleaved = false
                val starts = mutableListOf<AgentRuntimeWire.RunRequest>()
                fixture.beforeDispatch = { message ->
                    when {
                        message.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS && !interleaved && hasPendingResume(app) -> {
                            interleaved = true
                            settlementJobs = scope.coroutineContext[Job]!!.children.filterNot { it in beforeSettlement }.toList()
                            app.sendCurrentMessage("N")
                            true // Real query returns idle; N already owns the UI writer.
                        }
                        message.what == AgentRuntimeWire.MSG_START_RUN -> {
                            starts += AgentRuntimeWire.runRequestFromBundle(message.data)
                            false // Keep the new request in flight without contacting a model.
                        }
                        else -> true
                    }
                }
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "done"))
                fixture.await { interleaved && starts.isNotEmpty() && settlementJobs.isNotEmpty() && settlementJobs.all { it.isCompleted } }
                recoveryFlag(app).set(false)
                refreshAndAwait(fixture, app)
                assertEquals(listOf("N"), starts.map { it.prompt })
                assertTrue(app.homeState.isStreaming)
                assertEquals(listOf(queuedId), app.homeState.queuedMessages.map { it.id })
                assertEquals("Q", app.homeState.queuedMessages.single().content)
            } finally { cancelAndAwait(fixture, scope) }
        }
    }

    @Test fun oneUnavailableSettlementQueryRecoversAndStartsQueuedTurnExactlyOnce() {
        RuntimeMessengerFixture().use { fixture ->
            configureModel(fixture)
            val request = fixture.request("queue-reconnect", "queue-reconnect-key")
            val run = fixture.admit(request).also { it.prepared(request) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(run.runId) })
                app.sendCurrentMessage("Q after reconnect")
                fixture.await { !isRecovering(app) }
                // Hold the existing recovery gate so an unrelated terminal-revision
                // reconciliation cannot bypass the one intentionally failed query.
                recoveryFlag(app).set(true)
                val beforeSettlement = scope.coroutineContext[Job]!!.children.toSet()
                var settlementJobs = emptyList<Job>()
                var dropped = false
                var droppedAt = 0L
                val starts = mutableListOf<AgentRuntimeWire.RunRequest>()
                fixture.beforeDispatch = { message ->
                    when {
                        message.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS && !dropped && hasPendingResume(app) -> {
                            dropped = true
                            droppedAt = System.nanoTime()
                            settlementJobs = scope.coroutineContext[Job]!!.children.filterNot { it in beforeSettlement }.toList()
                            false // The real client reports Unavailable after its bounded timeout.
                        }
                        message.what == AgentRuntimeWire.MSG_START_RUN -> {
                            starts += AgentRuntimeWire.runRequestFromBundle(message.data)
                            false
                        }
                        else -> true
                    }
                }
                fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "done"))
                fixture.await { dropped && System.nanoTime() - droppedAt >=
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(8_100) &&
                    settlementJobs.isNotEmpty() && settlementJobs.all { it.isCompleted } }
                assertTrue(starts.isEmpty())
                assertEquals("Q after reconnect", app.homeState.queuedMessages.single().content)
                recoveryFlag(app).set(false)
                app.refreshRuntimeResults()
                fixture.await { starts.isNotEmpty() }
                assertEquals(listOf("Q after reconnect"), starts.map { it.prompt })
                assertTrue(app.homeState.queuedMessages.isEmpty())
                refreshAndAwait(fixture, app)
                assertFalse(hasPendingResume(app))
                assertEquals(1, starts.size)
            } finally { cancelAndAwait(fixture, scope) }
        }
    }

    @Test fun busySameKeyArchiveDoesNotBlockIndependentTerminalConversationOrAckPredecessor() {
        RuntimeMessengerFixture().use { fixture ->
            val aRequest = fixture.request("old-a", "shared-key")
            val a = fixture.admit(aRequest).also { it.prepared(aRequest) }
            fixture.complete(a, AgentRuntimeWire.RunResult(a.runId, true, "old answer"))
            val bRequest = fixture.request("active-b", "shared-key")
            val b = fixture.admit(bRequest).also { it.prepared(bRequest) }
            val cRequest = fixture.request("independent-c", "independent-key")
            val c = fixture.admit(cRequest).also { it.prepared(cRequest) }
            fixture.complete(c, AgentRuntimeWire.RunResult(c.runId, true, "independent answer"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(c.runId) })
                assertEquals(AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                    "independent-key"), app.conversationPaneState.selectedConversationId)
                assertEquals("independent answer", app.homeState.messages.filterIsInstance<AgentMessageUi>().single().content)
                assertTrue(AgentRuntimeResultStore.list(fixture.context).any { it.result.runId == a.runId })
                assertTrue(AgentRunArchiveStore.list(fixture.context).any { it.result.runId == a.runId })
                assertFalse(b.session.controller.isCancelled)
                assertTrue(fixture.registry.snapshots().any { it.runId == b.runId })
                fixture.complete(b, AgentRuntimeWire.RunResult(b.runId, true, "successor answer"))
            } finally { cancelAndAwait(fixture, scope) }
        }
    }

    @Test fun externalSuccessorStopWithFailedSaveCannotResumeFromPredecessorToken() {
        successorStopDoesNotResumeQueue(failSave = true)
    }

    @Test fun externalSuccessorStopWithSuccessfulSaveKeepsQueueStopped() {
        successorStopDoesNotResumeQueue(failSave = false)
    }

    private fun successorStopDoesNotResumeQueue(failSave: Boolean) {
        RuntimeMessengerFixture().use { fixture ->
            configureModel(fixture)
            // ACK deduplication is process-wide, even when a test recreates its database.
            val identity = if (failSave) "failed-save" else "saved"
            val key = "token-shared-$identity"
            val aRequest = fixture.request("token-a-$identity", key)
            val a = fixture.admit(aRequest).also { it.prepared(aRequest) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sql = EtaDatabase.get(fixture.context).openHelper.writableDatabase
            try {
                val app = AgentAppState(fixture.context, scope)
                assertTrue(ui(fixture, scope) { app.openRunConversation(a.runId) })
                fixture.await { !isRecovering(app) }
                app.sendCurrentMessage("Q must remain stopped")
                val queuedId = app.homeState.queuedMessages.single().id
                recoveryFlag(app).set(true)
                val beforeSettlement = scope.coroutineContext[Job]!!.children.toSet()
                var settlementJobs = emptyList<Job>()
                var heldQuery: android.os.Messenger? = null
                val starts = mutableListOf<AgentRuntimeWire.RunRequest>()
                fixture.beforeDispatch = { message ->
                    when {
                        message.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS && heldQuery == null && hasPendingResume(app) -> {
                            heldQuery = message.replyTo
                            settlementJobs = scope.coroutineContext[Job]!!.children.filterNot { it in beforeSettlement }.toList()
                            false
                        }
                        message.what == AgentRuntimeWire.MSG_START_RUN -> {
                            starts += AgentRuntimeWire.runRequestFromBundle(message.data)
                            false
                        }
                        else -> true
                    }
                }
                fixture.complete(a, AgentRuntimeWire.RunResult(a.runId, true, "A done"))
                fixture.await { heldQuery != null }
                assertTrue(hasPendingResume(app))
                val bRequest = fixture.request("token-b-$identity", key)
                val b = fixture.admit(bRequest).also { it.prepared(bRequest) }
                publish(fixture)
                assertTrue(ui(fixture, scope) { app.openRunConversation(b.runId) })
                heldQuery!!.send(android.os.Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                    data = AgentRuntimeWire.activeRunsToBundle(emptyList())
                })
                fixture.await { settlementJobs.isNotEmpty() && settlementJobs.all { it.isCompleted } }
                recoveryFlag(app).set(false)
                refreshAndAwait(fixture, app)
                assertFalse("B registration must invalidate A's token", hasPendingResume(app))
                assertEquals(listOf(queuedId), app.homeState.queuedMessages.map { it.id })
                if (failSave) {
                    // Room upsert can update an existing streaming placeholder after an
                    // INSERT constraint failure. Reject both writes to exercise failed save.
                    listOf("INSERT", "UPDATE").forEach { operation ->
                        sql.execSQL("CREATE TRIGGER reject_token_b_$operation BEFORE $operation ON conversation_messages " +
                            "WHEN NEW.id LIKE 'assistant-${b.runId}%' BEGIN SELECT RAISE(ABORT, 'fixture B save failure'); END")
                    }
                }
                app.stopCurrentRun()
                fixture.await { b.session.controller.isCancelled }
                fixture.complete(b, AgentRuntimeWire.RunResult(b.runId, false, "", "已停止"))
                assertTrue("B must enter the outbox before testing failed UI persistence",
                    AgentRuntimeResultStore.list(fixture.context).any { it.result.runId == b.runId })
                fixture.await { !app.homeState.isStreaming }
                refreshAndAwait(fixture, app)
                refreshAndAwait(fixture, app)
                assertEquals(listOf(queuedId), app.homeState.queuedMessages.map { it.id })
                assertEquals("Q must remain stopped", app.homeState.queuedMessages.single().content)
                assertTrue(starts.isEmpty())
                if (failSave) {
                    assertTrue(AgentRuntimeResultStore.list(fixture.context).any { it.result.runId == b.runId })
                    assertTrue(AgentRunArchiveStore.list(fixture.context).any { it.result.runId == b.runId })
                } else {
                    fixture.await { AgentRuntimeResultStore.list(fixture.context).none { it.result.runId == b.runId } &&
                        AgentRunArchiveStore.list(fixture.context).none { it.result.runId == b.runId } }
                }
            } finally {
                cancelAndAwait(fixture, scope)
                sql.execSQL("DROP TRIGGER IF EXISTS reject_token_b_INSERT")
                sql.execSQL("DROP TRIGGER IF EXISTS reject_token_b_UPDATE")
            }
        }
    }

    private fun configureModel(fixture: RuntimeMessengerFixture) {
        fixture.result(fixture.worker { runBlocking {
            ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(id = "queue-provider", name = "Fixture",
                baseUrl = "https://example.invalid", apiKey = "fixture",
                models = listOf(Model(id = "queue-model", modelId = "fixture", displayName = "Fixture"))))
            RuntimeConfigRepository.setSelectedProviderId("queue-provider")
            RuntimeConfigRepository.setSelectedModelId("queue-model")
        } })
    }

    private fun refreshAndAwait(fixture: RuntimeMessengerFixture, app: AgentAppState) {
        app.refreshRuntimeResults()
        fixture.await {
            !isRecovering(app) &&
                !(AgentAppState::class.java.getDeclaredField("runtimeRecoveryPending").apply { isAccessible = true }
                    .get(app) as java.util.concurrent.atomic.AtomicBoolean).get()
        }
    }

    private fun cancelAndAwait(fixture: RuntimeMessengerFixture, scope: CoroutineScope) {
        val job = requireNotNull(scope.coroutineContext[Job])
        scope.cancel()
        fixture.await { job.isCompleted }
    }

    private fun hasPendingResume(app: AgentAppState): Boolean =
        (AgentAppState::class.java.getDeclaredField("pendingQueueResumes").apply { isAccessible = true }
            .get(app) as Map<*, *>).isNotEmpty()

    private fun isRecovering(app: AgentAppState): Boolean =
        recoveryFlag(app).get()

    private fun recoveryFlag(app: AgentAppState): java.util.concurrent.atomic.AtomicBoolean =
        (AgentAppState::class.java.getDeclaredField("runtimeRecoveryInProgress").apply { isAccessible = true }
            .get(app) as java.util.concurrent.atomic.AtomicBoolean)

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
