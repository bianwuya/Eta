package io.github.mangi.eta.local.conversationcommunication

/** 目标会话当前的运行状态；由 Runtime 依据活跃 run 与用户显式停止标记给出。 */
internal enum class ConversationTargetState { IDLE, RUNNING, PAUSED, STOPPED }

internal data class ConversationTargetRunState(
    val state: ConversationTargetState,
    val runId: String = "",
)

internal sealed interface ConversationDelivery {
    /** 目标已有运行中的 run：排入其完整 turn 边界队列，不取消也不重启在途请求。 */
    data class IntoRun(val runId: String) : ConversationDelivery

    /** 目标空闲：按现有发送链路开启新 run。 */
    data object StartRun : ConversationDelivery

    /** 用户显式暂停/停止、或运行状态不可用：只排队，不擅自恢复。 */
    data class QueueOnly(val reason: String) : ConversationDelivery

    data class Reject(val code: String, val message: String) : ConversationDelivery
}

internal object ConversationDeliveryPolicy {
    const val SAME_CONVERSATION = "SAME_CONVERSATION"
    const val UNKNOWN_CONVERSATION = "UNKNOWN_CONVERSATION"
    const val TARGET_RUN_UNAVAILABLE = "TARGET_RUN_UNAVAILABLE"
    const val TARGET_PAUSED = "TARGET_PAUSED"
    const val TARGET_STOPPED = "TARGET_STOPPED"
    const val TARGET_STATE_UNKNOWN = "TARGET_STATE_UNKNOWN"
    const val TARGET_READ_ONLY = "TARGET_READ_ONLY"

    /**
     * 用户显式 paused/stopped 的目标默认只排队：当前没有“逐会话恢复”的授权语义，
     * 因此不提供自动唤醒开关，也不把排队消息当作可恢复许可。
     *
     * 没有本机终态记录（新装、记录被淘汰、进程外停止）时同样只排队：
     * 无法区分“自然结束”和“被用户停止”，不能拿推测换一次擅自唤醒。
     */
    fun decide(
        sourceConversationId: String,
        targetConversationId: String,
        targetExists: Boolean,
        target: ConversationTargetRunState,
        lastOutcome: ConversationTerminalOutcome?,
        targetReadOnly: Boolean = false,
    ): ConversationDelivery {
        if (targetConversationId.isBlank()) {
            return ConversationDelivery.Reject(UNKNOWN_CONVERSATION, "目标会话不存在，请先用 conversation_list 获取准确 ID")
        }
        if (targetConversationId == sourceConversationId) {
            return ConversationDelivery.Reject(SAME_CONVERSATION, "不能给当前会话本身发送跨会话消息")
        }
        if (!targetExists) {
            return ConversationDelivery.Reject(UNKNOWN_CONVERSATION, "目标会话不存在，请先用 conversation_list 获取准确 ID")
        }
        if (targetReadOnly) {
            return ConversationDelivery.Reject(TARGET_READ_ONLY, "目标是只读归档或语音会话，不能接收跨会话消息")
        }
        return when (target.state) {
            ConversationTargetState.RUNNING -> target.runId.takeIf(String::isNotBlank)
                ?.let { ConversationDelivery.IntoRun(it) }
                ?: ConversationDelivery.QueueOnly(TARGET_RUN_UNAVAILABLE)
            ConversationTargetState.PAUSED -> ConversationDelivery.QueueOnly(TARGET_PAUSED)
            ConversationTargetState.STOPPED -> ConversationDelivery.QueueOnly(TARGET_STOPPED)
            ConversationTargetState.IDLE -> when (lastOutcome) {
                ConversationTerminalOutcome.STOPPED -> ConversationDelivery.QueueOnly(TARGET_STOPPED)
                ConversationTerminalOutcome.PAUSED -> ConversationDelivery.QueueOnly(TARGET_PAUSED)
                ConversationTerminalOutcome.COMPLETED,
                ConversationTerminalOutcome.FAILED -> ConversationDelivery.StartRun
                null -> ConversationDelivery.QueueOnly(TARGET_STATE_UNKNOWN)
            }
        }
    }

    fun queueReasonText(reason: String): String = when (reason) {
        TARGET_PAUSED -> "目标会话已被用户暂停，消息已排队，不会自动恢复该会话"
        TARGET_STOPPED -> "目标会话已被用户停止，消息已排队，不会自动唤醒该会话"
        TARGET_RUN_UNAVAILABLE -> "目标会话运行状态暂不可用，消息已排队"
        TARGET_STATE_UNKNOWN ->
            "无法确认目标会话是否被用户停止（本机没有该会话的终态记录），消息只排队，不会自动唤醒；" +
                "用户下次在该会话发送消息后才会送达"
        else -> "目标会话当前无法接收，消息已排队"
    }
}
