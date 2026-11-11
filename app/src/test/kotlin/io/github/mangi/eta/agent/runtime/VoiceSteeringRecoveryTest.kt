package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.db.EtaDatabase
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
class VoiceSteeringRecoveryTest {
    @Before fun reset() { EtaDatabase.closeForTests(); RuntimeEnvironment.getApplication().deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun acceptedUnconsumedInputSurvivesDatabaseReopenAndInterruptedArchive() {
        RuntimeMessengerFixture().use { f ->
            val request = f.request("voice-crash")
            val run = f.admit(request)
            run.prepared(request)
            val outcome = f.result(f.worker { f.client.steerRun(run.runId, "accepted", "new direction") })
            assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted, outcome)
            val firstRecorder = run.session.initializePersistence(f.context, request)
            assertSame(firstRecorder, run.session.initializePersistence(f.context, request))
            val beforeClose = AgentRunCheckpointStore.list(f.context)
            assertEquals("accepted steering must already be durable before close", 1, beforeClose.size)
            val originalDatabase = EtaDatabase.get(f.context).openHelper.writableDatabase.path
            EtaDatabase.closeForTests()
            val afterClose = AgentRunCheckpointStore.list(f.context)
            assertEquals("checkpoint changed after reopen: old=$originalDatabase new=" +
                EtaDatabase.get(f.context).openHelper.writableDatabase.path, beforeClose, afterClose)
            val checkpoint = afterClose.single()
            val event = checkpoint.events.filterIsInstance<AgentEvent.UserSupplementReceived>().single()
            assertEquals("accepted", event.queueMessageId)
            assertTrue(run.session.controller.hasPendingSteering)
            AgentRunCheckpointStore.archiveInterruptedExternal(f.context, listOf(checkpoint), emptySet(), emptySet())
            val archived = AgentRunArchiveStore.list(f.context).single()
            assertEquals(request.handoff, archived.handoff)
            assertEquals(listOf(event), archived.events)
            assertFalse(archived.result.ok)
            assertTrue(AgentRunCheckpointStore.list(f.context).isEmpty())
        }
        EtaDatabase.closeForTests()
        RuntimeMessengerFixture().use { f ->
            val status = f.result(f.worker { f.client.querySteering("voice-crash", "accepted") })
            assertTrue(status is AgentRuntimeClient.SteeringStatus.Accepted)
            assertEquals(AgentRuntimeClient.SteeringStatus.NotAccepted,
                f.result(f.worker { f.client.querySteering("voice-crash", "never-sent") }))
            assertEquals(AgentRuntimeClient.SteeringStatus.Unavailable,
                f.result(f.worker { f.client.querySteering("unknown-run", "never-sent") }))
            assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
        }
    }

    @Test fun terminalArtifactsRetainSupplementBeforeModelConsumption() {
        RuntimeMessengerFixture().use { f ->
            val request = f.request("voice-terminal")
            val run = f.admit(request)
            run.prepared(request)
            assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted,
                f.result(f.worker { f.client.steerRun(run.runId, "queue", "guidance") }))
            f.complete(run, AgentRuntimeWire.RunResult(run.runId, false, "", error = "fixture stopped"))
            assertEquals("queue", AgentRunArchiveStore.list(f.context).single().events
                .filterIsInstance<AgentEvent.UserSupplementReceived>().single().queueMessageId)
            assertTrue(f.result(f.worker { f.client.querySteering(run.runId, "queue") }) is AgentRuntimeClient.SteeringStatus.Accepted)
        }
    }

    @Test fun preparationUnsupportedModesAndEndedRunReportRealReasons() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("voice-preparing"))
            assertEquals(AgentRuntimeClient.SteeringOutcome.Preparing,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "text") }))
            run.prepared(run.request)
            assertEquals(AgentRuntimeClient.SteeringStatus.NotAccepted,
                f.result(f.worker { f.client.querySteering(run.runId, "q") }))
            run.session.controller.pollSteeringInputOrSeal()
            assertEquals(AgentRuntimeClient.SteeringOutcome.NotAccepted,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "text") }))
            for ((id, request) in listOf(
                "external" to f.request("other").let { it.copy(handoff = it.handoff!!.copy(source = "other")) },
                "compact" to f.request("compact").copy(operation = AgentRuntimeWire.OP_COMPACT),
                "rewrite" to f.request("rewrite").copy(operation = AgentRuntimeWire.OP_REWRITE_REPLY),
                "plan" to f.request("plan").copy(requestMode = "plan"),
            )) {
                val unsupported = f.admit(request); unsupported.prepared(request)
                assertEquals(id, AgentRuntimeClient.SteeringOutcome.Unsupported,
                    f.result(f.worker { f.client.steerRun(unsupported.runId, "q", "text") }))
                assertFalse(unsupported.session.controller.hasPendingSteering)
            }
        }
    }

    @Test fun lostAcknowledgementIsReconciledWithoutResendingAndMissingRequestIsSafeToRetry() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("lost-ack")); run.prepared(run.request)
            f.beforeDispatch = { msg ->
                if (msg.what == AgentRuntimeWire.MSG_STEER_RUN) msg.replyTo = null
                true
            }
            assertEquals(AgentRuntimeClient.SteeringOutcome.Unavailable,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "guidance") }))
            val status = f.result(f.worker { f.client.querySteering(run.runId, "q") })
            assertTrue(status is AgentRuntimeClient.SteeringStatus.Accepted)
            assertEquals("guidance", run.session.controller.pollSteeringMessage())
            assertNull(run.session.controller.pollSteeringInput())
            assertEquals(1, f.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })
            f.beforeDispatch = { msg -> msg.what != AgentRuntimeWire.MSG_STEER_RUN }
            assertEquals(AgentRuntimeClient.SteeringOutcome.Unavailable,
                f.result(f.worker { f.client.steerRun(run.runId, "missing", "not delivered") }))
            assertEquals(AgentRuntimeClient.SteeringStatus.NotAccepted,
                f.result(f.worker { f.client.querySteering(run.runId, "missing") }))
            assertNull(run.session.controller.pollSteeringInput())
        }
    }

    @Test fun failedDurableAdmissionLeavesNoDedupMarkerReplayOrControllerInput() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("storage-failure")); run.prepared(run.request)
            run.session.initializePersistence(f.context, run.request)
            EtaDatabase.get(f.context).openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_supplement BEFORE INSERT ON runtime_inflight_events " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertEquals(AgentRuntimeClient.SteeringOutcome.StorageFailed,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "guidance") }))
            assertNull(run.acceptedSupplement("q"))
            assertNull(run.session.controller.pollSteeringInput())
            val replay = mutableListOf<AgentEvent>(); run.session.attach(replay::add, {})
            assertTrue(replay.isEmpty())
            EtaDatabase.get(f.context).openHelper.writableDatabase.execSQL("DROP TRIGGER reject_supplement")
            assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted,
                f.result(f.worker { f.client.steerRun(run.runId, "q", "guidance") }))
            assertEquals(1, run.acceptedSupplement("q")!!.index)
        }
    }

    @Test fun recreatedMainChatImportsUnconsumedVoiceGuidanceExactlyOnce() {
        val id = "recreated-main"
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request(id)); run.prepared(run.request)
            assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted,
                f.result(f.worker { f.client.steerRun(id, "q", "accepted before crash") }))
            f.complete(run, AgentRuntimeWire.RunResult(id, false, "", error = "interrupted"))
        }
        EtaDatabase.closeForTests()
        RuntimeMessengerFixture().use { f ->
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
            try {
                val app = io.github.mangi.eta.ui.app.AgentAppState(f.context, scope)
                f.await { AgentRuntimeWire.MSG_ACK_RESULT in f.messages }
                val conversation = AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, id)
                val restored = f.result(f.worker { kotlinx.coroutines.runBlocking {
                    io.github.mangi.eta.ui.app.AgentConversationStore.loadConversation(f.context, conversation)
                } })!!
                val user = restored.messages.filterIsInstance<io.github.mangi.eta.ui.model.UserMessageUi>()
                    .single { it.id == "user-$id-supplement-1" }
                assertEquals("accepted before crash", user.content)
                assertEquals(1, restored.history.count { it.messageId == user.id })
                assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun largeAcceptedImageEventRoundTripsThroughChunkedCheckpointAndArchive() {
        RuntimeMessengerFixture().use { f ->
            val request = f.request("large-image")
            val run = f.admit(request); run.prepared(request)
            run.session.initializePersistence(f.context, request)
            val image = "data:image/png;base64," + "A".repeat(1_200_000)
            val accepted = run.supplement("", "image-q", listOf(io.github.mangi.eta.agent.model.AgentModelClient.ModelImage(
                image, "image/png", 900_000)), listOf(image))!!
            EtaDatabase.closeForTests()
            assertEquals(accepted, AgentRunCheckpointStore.list(f.context).single().events.single())
            f.complete(run, AgentRuntimeWire.RunResult(run.runId, false, "", error = "fixture"))
            EtaDatabase.closeForTests()
            assertEquals(listOf(image), AgentRunArchiveStore.list(f.context).single().events
                .filterIsInstance<AgentEvent.UserSupplementReceived>().single().images)
        }
    }

    @Test fun persistenceFailureCannotPublishOrEnqueueGuidance() {
        val controller = AgentRunController()
        assertThrows(IllegalStateException::class.java) {
            controller.steer("guidance") { error("fixture storage failure") }
        }
        assertFalse(controller.hasPendingSteering)
        assertNull(controller.pollSteeringInput())
        assertEquals(0L, controller.steeringVersion)
        assertTrue(controller.steer("retry"))
        assertEquals("retry", controller.pollSteeringMessage())
    }
}
