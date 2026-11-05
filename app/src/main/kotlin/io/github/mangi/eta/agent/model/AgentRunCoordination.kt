package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/** Local coordination only; implementations do not call a model. Invoked between complete turns. */
internal interface AgentRunCoordination {
    fun checkpoint() {}
    fun drainMessages(): List<String> = emptyList()
    fun requestStarted() {}
    fun responseReceived(usage: AgentTokenUsage?) {}
    fun beforeFinish(): List<String> = emptyList()
}
