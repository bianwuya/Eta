package io.github.mangi.eta.agent.runtime

/** Native notification selection is independent of execution order and GUI ownership. */
internal object RuntimeNotificationSelection {
    fun select(runs: List<AgentRuntimeWire.ActiveRunSnapshot>): AgentRuntimeWire.ActiveRunSnapshot? =
        runs.maxWithOrNull(compareBy<AgentRuntimeWire.ActiveRunSnapshot> {
            it.handoffSource == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        }.thenBy { it.interactionAt }.thenBy { it.createdAt }.thenBy { it.runId })
}
