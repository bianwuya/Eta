package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient

/** Display history is not working context. Never resurrect the prefix replaced by a snapshot. */
internal object SubagentContinuation {
    fun history(
        prior: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot?,
    ): List<AgentModelClient.ConversationMessage> {
        if (snapshot == null) return prior + AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) + transcript
        val consumed = requireNotNull(snapshot.consumedTranscriptMessages) { "压缩快照缺少 transcript 覆盖边界" }
        require(consumed in 0..transcript.size) { "压缩快照的 transcript 覆盖边界无效" }
        return snapshot.messages + transcript.drop(consumed)
    }
}
