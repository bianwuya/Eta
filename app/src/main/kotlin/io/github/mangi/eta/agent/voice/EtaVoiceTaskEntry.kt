package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** Local submissions fill only the window before Runtime lists their exact IDs. */
internal data class EtaVoiceTaskList(
    val known: Boolean,
    val rows: List<Row>,
) {
    data class Row(val run: AgentRuntimeWire.ActiveRunSnapshot, val admitted: Boolean = true)

    val canOpenInputAutomatically: Boolean get() = known && rows.isEmpty()

    fun visibleRows(all: Boolean): List<Row> = if (all) rows else rows.filter { it.run.isVoiceTask() }

    companion object {
        fun from(
            query: AgentRuntimeClient.ActiveRunsQuery,
            submitted: Collection<AgentRuntimeWire.ActiveRunSnapshot>,
        ): EtaVoiceTaskList {
            val runtime = query as? AgentRuntimeClient.ActiveRunsQuery.Known
            val rows = runtime?.runs.orEmpty().distinctBy { it.runId }.map(::Row)
            val reported = rows.mapTo(hashSetOf()) { it.run.runId }
            return EtaVoiceTaskList(runtime != null, rows + submitted.filter { it.runId !in reported }
                .map { Row(it.copy(phase = AgentRuntimeWire.RunPhase.Preparing), admitted = false) })
        }
    }
}

internal fun AgentRuntimeWire.ActiveRunSnapshot.isVoiceTask(): Boolean =
    handoffSource == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

internal fun AgentRuntimeClient.ActiveRunsQuery.canGuideVoiceTask(target: AgentRuntimeWire.ActiveRunSnapshot): Boolean =
    target.isVoiceTask() && target.conversationId.isNotBlank() && this is AgentRuntimeClient.ActiveRunsQuery.Known &&
        runs.any { it.runId == target.runId && it.conversationId == target.conversationId && it.isVoiceTask() &&
            it.operation == AgentRuntimeWire.OP_CHAT && it.phase == AgentRuntimeWire.RunPhase.Running }

/** Main-confined gate at the boundary of the non-interruptible official run() call. */
internal class EtaVoiceSubmissionGate {
    private enum class State { Preparing, Dispatching, Finished }
    private var state = State.Preparing
    var stopRequested = false
        private set

    fun beginDispatch(): Boolean {
        if (state != State.Preparing || stopRequested) return false
        state = State.Dispatching
        return true
    }

    /** If dispatch won the race, wait for this particular ID to become visible before cancelling. */
    fun requestStop(): Boolean {
        if (stopRequested || state == State.Finished) return false
        stopRequested = true
        return state == State.Dispatching
    }

    val awaitingStop: Boolean get() = stopRequested && state == State.Dispatching

    fun finish() { state = State.Finished }
}
