package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunPhase
import io.github.mangi.eta.ui.model.QueuedMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production policy and coordinator with in-memory storage and no Android service. */
class EtaVoiceTaskEntryTest {
    private val a = voiceRun("run-aaaa-1")
    private val b = voiceRun("run-aaaa-2") // Same displayed prefix is not a routing key.
    private val chat = voiceRun("chat-run").copy(handoffSource = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)

    @Test
    fun pendingLocalRunAndUnavailableQueryCannotAutoOpenTheInput() {
        val pending = EtaVoiceTaskList.from(known(), listOf(a))
        assertFalse(pending.canOpenInputAutomatically)
        assertEquals(RunPhase.Preparing, pending.rows.single().run.phase)
        assertFalse(pending.rows.single().admitted)
        assertFalse(EtaVoiceTaskList.from(AgentRuntimeClient.ActiveRunsQuery.Unavailable, emptyList()).canOpenInputAutomatically)
        assertTrue(EtaVoiceTaskList.from(known(), emptyList()).canOpenInputAutomatically)
    }

    @Test
    fun allIncludesNonVoiceAndExactRunsWithSharedConversationId() {
        val second = b.copy(conversationId = a.conversationId, phase = RunPhase.WaitingForGui)
        val listing = EtaVoiceTaskList.from(known(a, chat, second), listOf(a))
        assertEquals(listOf(a, second), listing.visibleRows(all = false).map { it.run })
        assertEquals(listOf(a, chat, second), listing.visibleRows(all = true).map { it.run })
        assertTrue(listing.rows.all { it.admitted })
    }

    @Test
    fun guidanceRequiresExactRunConversationVoiceSourceChatOperationAndRunningPhase() {
        assertTrue(known(chat, a, b).canGuideVoiceTask(b))
        assertFalse(known(b.copy(runId = a.runId)).canGuideVoiceTask(b))
        assertFalse(known(b.copy(conversationId = a.conversationId)).canGuideVoiceTask(b))
        assertFalse(known(b.copy(handoffSource = chat.handoffSource)).canGuideVoiceTask(b))
        assertFalse(known(b.copy(operation = AgentRuntimeWire.OP_COMPACT)).canGuideVoiceTask(b))
        assertFalse(AgentRuntimeClient.ActiveRunsQuery.Unavailable.canGuideVoiceTask(b))
        RunPhase.entries.filterNot { it == RunPhase.Running }.forEach { phase ->
            assertFalse("phase=$phase", known(b.copy(phase = phase)).canGuideVoiceTask(b))
        }
    }

    @Test
    fun explicitStopDuringPreparationPreventsDispatchOfOnlyThatRequest() {
        val stopped = EtaVoiceSubmissionGate()
        val other = EtaVoiceSubmissionGate()
        assertFalse(stopped.requestStop())
        assertTrue(stopped.stopRequested)
        assertFalse(stopped.beginDispatch())
        assertTrue(other.beginDispatch())
        assertFalse(other.stopRequested)
    }

    @Test
    fun explicitStopDuringDispatchWaitsForThatRunAndStopsWatchingAfterResult() {
        val gate = EtaVoiceSubmissionGate()
        assertTrue(gate.beginDispatch())
        assertTrue(gate.requestStop())
        assertFalse(gate.requestStop())
        assertTrue(gate.awaitingStop)
        gate.finish()
        assertFalse(gate.awaitingStop)
        assertFalse(gate.beginDispatch())
    }

    @Test
    fun aStorageFailureDoesNotSendOrClearTheDraft() = runBlocking {
        val fixture = SteeringFixture(known(a))
        fixture.failWrite = true
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.SaveFailed, fixture.submit(a))
        assertTrue(fixture.sent.isEmpty())
        assertEquals(0, fixture.persistedCallbacks)
    }

    @Test
    fun preparingOrUnknownNeverStagesOrSends() = runBlocking {
        for (query in listOf(known(a.copy(phase = RunPhase.Preparing)), AgentRuntimeClient.ActiveRunsQuery.Unavailable)) {
            val fixture = SteeringFixture(query)
            assertEquals(EtaAssistantSteeringCoordinator.Outcome.NotReady, fixture.submit(a))
            assertTrue(fixture.rows.isEmpty())
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun phaseChangeAfterDurableStagingPreventsDispatch() = runBlocking {
        val fixture = SteeringFixture(known(a), known(a.copy(phase = RunPhase.Committing)))
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.NotReady, fixture.submit(a))
        assertEquals(1, fixture.staged.size)
        assertTrue(fixture.rows[a.conversationId].orEmpty().isEmpty())
        assertTrue(fixture.sent.isEmpty())
        assertEquals(0, fixture.persistedCallbacks)
    }

    @Test
    fun switchingAwayDuringTheQueryDoesNotSendToEitherRun() = runBlocking {
        val fixture = SteeringFixture(known(a, b), known(a, b))
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.NotReady, fixture.submit(a) { fixture.staged.isEmpty() })
        assertEquals(1, fixture.staged.size)
        assertTrue(fixture.rows[a.conversationId].orEmpty().isEmpty())
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun unknownDeliveryQuarantinesAcrossReentryUntilTheExactRunAndReceiptMatch() = runBlocking {
        val fixture = SteeringFixture(known(a, b))
        val second = b.copy(conversationId = a.conversationId)
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.Unconfirmed, fixture.submit(a))
        val receipt = fixture.rows.getValue(a.conversationId).single()
        assertEquals(a.runId, receipt.steeringRunId)
        assertTrue(receipt.steeringUnconfirmed)
        assertEquals(listOf(a.runId to receipt.id), fixture.sent)
        fixture.newCoordinator().acknowledge(second, receipt.id)
        fixture.newCoordinator().acknowledge(a, "not-the-receipt")
        assertEquals(listOf(receipt), fixture.rows.getValue(a.conversationId))
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.PendingReceipt, fixture.submit(a))
        assertEquals(1, fixture.sent.size)
        assertEquals(listOf(receipt), fixture.rows.getValue(a.conversationId))
        fixture.newCoordinator().acknowledge(a, receipt.id)
        assertTrue(fixture.rows.getValue(a.conversationId).isEmpty())
    }

    @Test
    fun positiveReplyAndDefiniteRejectionSettleOnlyTheNewExactId() = runBlocking {
        val fixture = SteeringFixture(known(a, b))
        val other = QueuedMessageUi(id = "unconfirmed", content = "previous instruction", steeringRunId = b.runId)
        fixture.rows[a.conversationId] = listOf(other)
        fixture.reply = AgentRuntimeClient.SteerOutcome.Accepted(1)
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.Accepted, fixture.submit(a))
        assertEquals(listOf(other), fixture.rows.getValue(a.conversationId))
        fixture.reply = AgentRuntimeClient.SteerOutcome.Rejected
        assertEquals(EtaAssistantSteeringCoordinator.Outcome.Rejected, fixture.submit(a))
        assertEquals(listOf(other), fixture.rows.getValue(a.conversationId))
        assertEquals(2, fixture.sent.distinct().size)
    }

    private class SteeringFixture(vararg queries: AgentRuntimeClient.ActiveRunsQuery) {
        var reply: AgentRuntimeClient.SteerOutcome = AgentRuntimeClient.SteerOutcome.Unavailable
        var failWrite = false
        var persistedCallbacks = 0
        val rows = mutableMapOf<String, List<QueuedMessageUi>>()
        val staged = mutableListOf<QueuedMessageUi>()
        val sent = mutableListOf<Pair<String, String>>()
        private var queryIndex = 0
        private val responses = queries.toList()

        suspend fun submit(run: AgentRuntimeWire.ActiveRunSnapshot, selected: () -> Boolean = { true }) =
            newCoordinator().submit(run, "one instruction", selected) { persistedCallbacks++ }

        fun newCoordinator() = EtaAssistantSteeringCoordinator(
            query = { responses[queryIndex++.coerceAtMost(responses.lastIndex)] },
            send = { runId, text, id ->
                assertTrue(rows.values.flatten().any { it.id == id && it.steeringRunId == runId && it.content == text })
                assertTrue(persistedCallbacks > 0)
                sent += runId to id
                reply
            },
            edit = { owner, transform ->
                check(!failWrite) { "fixture transaction failed" }
                val previous = rows[owner].orEmpty()
                val next = transform(previous)
                if (next == null) false else {
                    staged += next - previous.toSet()
                    rows[owner] = next
                    true
                }
            },
        )
    }

    private fun voiceRun(id: String) = AgentRuntimeWire.ActiveRunSnapshot(
        runId = id,
        conversationId = "conversation-for-$id",
        handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
    )

    private fun known(vararg runs: AgentRuntimeWire.ActiveRunSnapshot) = AgentRuntimeClient.ActiveRunsQuery.Known(runs.toList())
}
