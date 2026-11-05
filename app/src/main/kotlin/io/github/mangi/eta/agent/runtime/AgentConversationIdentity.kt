package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"
    const val VOICE_PREFIX = "assistant-"

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return "run:$fallbackRunId"
    }

    /** 语音助手与外部归档会话是只读投影，不能作为跨会话消息的目标被唤醒。 */
    fun isArchivedConversationId(conversationId: String): Boolean =
        conversationId.startsWith(EXTERNAL_ARCHIVE_PREFIX) || conversationId.startsWith(VOICE_PREFIX)

    fun archiveConversationId(source: String, conversationKey: String): String {
        val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) VOICE_PREFIX else EXTERNAL_ARCHIVE_PREFIX
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return prefix + digest
    }
}
