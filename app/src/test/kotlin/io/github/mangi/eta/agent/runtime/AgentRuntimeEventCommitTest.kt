package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import java.lang.reflect.InvocationTargetException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeEventCommitTest {
    private lateinit var context: Context
    private lateinit var request: AgentRuntimeWire.RunRequest
    private lateinit var recorder: AgentRunCheckpointRecorder
    private lateinit var executor: AgentRuntimeRunExecutor

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        request = AgentRuntimeWire.RunRequest("event-stop-boundary", "inspect", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            emptyList(), handoff = AgentRuntimeWire.EntryHandoff("event-stop-boundary", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "fixture-chat"))
        recorder = requireNotNull(AgentRunCheckpointRecorder.create(context, request))
        executor = AgentRuntimeRunExecutor(context,
            currentPermissions = { AgentRuntimePolicy.Permissions(terminalTools = false, browserTools = false, thinking = false) },
            snapshotRequest = { it }, guiControl = GuiControlCoordinator(), onAcceptedEvent = { _, _ -> },
            persistArtifacts = { _, _, _ -> })
    }

    @After fun cleanup() {
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
    }

    private fun accept(session: AgentRuntimeSession, event: AgentEvent, archived: MutableList<AgentEvent>) {
        AgentRuntimeRunExecutor::class.java.getDeclaredMethod("acceptEvent", AgentRuntimeSession::class.java,
            AgentEvent::class.java, MutableList::class.java, EntrySurfaceGuard::class.java, AgentRunCheckpointRecorder::class.java)
            .apply { isAccessible = true }.invoke(executor, session, event, archived, null, recorder)
    }

    @Test fun stopRejectedFinishedEventIsNeverCheckpointedButPartialDataAndStoppedOutboxSurvive() {
        val events = mutableListOf<AgentEvent>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val archived = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession(request.runId, eventSink = events::add, resultSink = results::add)
        val partial = listOf(AgentModelClient.ConversationMessage("assistant", "partial answer"))
        val snapshot = AgentContextSnapshot(operationId = request.runId, messages = partial)
        session.commitTranscript(partial) { assertTrue(AgentRunCheckpointStore.saveTranscript(context, request.runId, partial)) }
        session.commitContext(snapshot) { AgentRunCheckpointStore.saveContext(context, request.runId, snapshot) }
        accept(session, AgentEvent.RoundStarted(1, 1), archived)
        assertTrue(session.requestStop())
        val error = assertThrows(InvocationTargetException::class.java) { accept(session, AgentEvent.RunFinished(1, 14), archived) }
        assertTrue(error.cause is AgentRunCancelledException)
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertTrue(checkpoint.events.none { it is AgentEvent.RunFinished })
        assertTrue(events.none { it is AgentEvent.RunFinished }); assertTrue(archived.none { it is AgentEvent.RunFinished })
        assertEquals(partial, checkpoint.transcript); assertEquals(snapshot, checkpoint.contextSnapshot)
        assertTrue(session.complete(AgentRuntimeWire.RunResult(request.runId, true, "late success",
            transcript = partial, contextSnapshot = snapshot)) { committed ->
            assertFalse(committed.ok)
            assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(request.handoff!!, committed, 1L)))
        })
        EtaDatabase.closeForTests()
        val stored = AgentRuntimeResultStore.list(context).single().result
        assertEquals(results.single(), stored); assertFalse(stored.ok); assertEquals("已停止", stored.error)
        assertEquals(partial, stored.transcript); assertEquals(snapshot, stored.contextSnapshot)
    }

    @Test fun admittedEventIsCheckpointedBeforeItIsPublishedAndReplayed() {
        val event = AgentEvent.RunFinished(1, 4)
        val archived = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession(request.runId, eventSink = { live ->
            assertTrue(AgentRunCheckpointStore.list(context).single().events.contains(live))
            delivered += live
        })
        accept(session, event, archived)
        assertEquals(listOf(event), delivered); assertEquals(listOf(event), archived)
        val replay = mutableListOf<AgentEvent>()
        assertTrue(session.attach(replay::add, {})); assertEquals(listOf(event), replay)
    }

    @Test fun terminalSessionRejectsEventBeforeRecorderOrLiveArchiveIsChanged() {
        val archived = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession(request.runId)
        assertTrue(session.complete(AgentRuntimeWire.RunResult(request.runId, true, "done")))
        accept(session, AgentEvent.RunFinished(1, 4), archived)
        assertTrue(AgentRunCheckpointStore.list(context).single().events.isEmpty())
        assertTrue(archived.isEmpty())
    }
}
