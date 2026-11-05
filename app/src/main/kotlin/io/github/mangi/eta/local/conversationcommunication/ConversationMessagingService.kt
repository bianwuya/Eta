package io.github.mangi.eta.local.conversationcommunication

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationSnapshot(
    val conversationId: String,
    val title: String,
    val updatedAt: Long,
    val modelId: String?,
    /** 只读归档/语音会话不能接收跨会话消息，也不能被唤醒。 */
    val readOnly: Boolean = false,
)

internal sealed interface ConversationStartResult {
    data class Started(val runId: String) : ConversationStartResult
    data class Rejected(val code: String, val message: String) : ConversationStartResult
}

/** Runtime 侧提供的最小能力：会话索引、运行状态、投递与续发。 */
internal interface ConversationMessagingHost {
    fun conversation(conversationId: String): ConversationSnapshot?
    fun conversations(limit: Int): List<ConversationSnapshot>
    fun runState(conversationId: String): ConversationTargetRunState

    /** 排入目标 run 的完整 turn 边界队列；run 已结束或已封口时返回 false。 */
    fun deliverIntoRun(runId: String, envelope: ConversationMessageEnvelope): Boolean

    /** 在目标会话按现有发送链路开启新 run；失败必须返回真实原因。 */
    fun startRunWithMessage(
        target: ConversationSnapshot,
        envelope: ConversationMessageEnvelope,
    ): ConversationStartResult
}

/**
 * 跨会话消息的读取与投递逻辑（不含 Android 依赖，便于针对性单测）。
 *
 * 语义约束：
 * - 运行中目标：排入完整 turn 边界，不取消/重启在途请求；
 * - 空闲且上一轮正常结束：按现有发送链路开启新 run；
 * - 用户显式暂停/停止、或无法确认终态：只排队，不自动唤醒；
 * - 由跨会话消息启动的 run 不能再发送消息，避免会话间自动互唤；
 * - 回执只描述真实投递动作，绝不表示对方已阅读。
 *
 * 投递顺序固定为“共享锁内校验并持久化预留（SENDING）→ 实际投递 → 更新状态”：
 * 并发同 ID 只会投递一次；投递后写状态失败会明确报告 outcome_unknown，且不会自动重投。
 */
internal class ConversationMessagingService(
    private val sourceConversationId: String,
    private val sourceTitle: String,
    private val sourceRunId: String,
    private val host: ConversationMessagingHost,
    private val outbox: ConversationMessageOutbox,
    private val allowSend: Boolean,
    private val newMessageId: () -> String = { "cm-${UUID.randomUUID()}" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun list(query: String, limit: Int): String {
        val safeLimit = limit.coerceIn(1, MAX_LIST)
        val keyword = query.trim()
        val available = host.conversations(safeLimit * 4)
        val conversations = available
            .asSequence()
            .filter { keyword.isBlank() || it.title.contains(keyword, ignoreCase = true) }
            .take(safeLimit)
            .toList()
        val items = JSONArray()
        var outboxAvailable = true
        conversations.forEach { conversation ->
            val state = host.runState(conversation.conversationId)
            val outcome = when (val stored = outbox.outcome(conversation.conversationId)) {
                is ConversationOutboxRead.Known -> stored.value?.outcome
                is ConversationOutboxRead.Unavailable -> {
                    outboxAvailable = false
                    null
                }
            }
            val deliveryHint = when (state.state) {
                ConversationTargetState.RUNNING -> "in_run"
                ConversationTargetState.PAUSED, ConversationTargetState.STOPPED -> "queue_only"
                ConversationTargetState.IDLE -> when (outcome) {
                    ConversationTerminalOutcome.COMPLETED,
                    ConversationTerminalOutcome.FAILED -> "start_run"
                    else -> "queue_only"
                }
            }
            items.put(JSONObject()
                .put("conversation_id", conversation.conversationId)
                .put("title", conversation.title.take(ConversationMessageEnvelope.MAX_TITLE_CHARS))
                .put("updated_at", conversation.updatedAt)
                .put("model_id", conversation.modelId ?: JSONObject.NULL)
                .put("read_only", conversation.readOnly)
                .put("run_state", state.state.name.lowercase())
                .put("active_run_id", state.runId.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("delivery_hint", deliveryHint)
                .put("is_current", conversation.conversationId == sourceConversationId))
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", TOOL_LIST)
            .put("conversations", items)
            .put("has_more", available.size > safeLimit)
            .put("outbox_available", outboxAvailable)
            .put("note", "conversation_id 是发送时必须使用的准确目标；delivery_hint=in_run 表示排入运行中任务，" +
                "start_run 表示会开启新运行，queue_only 表示只排队、不会自动唤醒该会话。" +
                if (outboxAvailable) "" else "投递记录当前不可读，发送会被拒绝。")
            .toString()
    }

    fun send(targetConversationId: String, text: String, requestedMessageId: String): String {
        if (!allowSend) {
            return error(
                "CONVERSATION_CHAIN_BLOCKED",
                "本运行由跨会话消息启动，为避免会话之间自动互唤，不能再向其他会话发送消息",
            )
        }
        val normalized = ConversationMessageEnvelope.normalizeText(text)
            ?: return error(
                "INVALID_CONVERSATION_MESSAGE",
                "消息必须是非空显式文本，且不超过 ${ConversationMessageEnvelope.MAX_TEXT_CHARS} 字符",
            )
        val targetId = targetConversationId.trim()
        if (targetId.isBlank()) {
            return error("UNKNOWN_CONVERSATION", "缺少目标 conversation_id，请先用 $TOOL_LIST 获取准确 ID")
        }
        val messageId = requestedMessageId.trim().ifBlank { newMessageId() }
        if (!ConversationMessageEnvelope.isValidMessageId(messageId)) {
            return error("INVALID_MESSAGE_ID", "message_id 只允许字母、数字与 _.:-，长度 " +
                "${ConversationMessageEnvelope.MIN_MESSAGE_ID_CHARS}–${ConversationMessageEnvelope.MAX_MESSAGE_ID_CHARS}")
        }
        val envelope = ConversationMessageEnvelope(
            messageId = messageId,
            sourceConversationId = sourceConversationId,
            sourceTitle = sourceTitle,
            sourceRunId = sourceRunId,
            targetConversationId = targetId,
            text = normalized,
            createdAt = now(),
        )
        // 同一 outbox 实例（同进程共享）串行整个“校验→预留→投递→更新”，并发同 ID 只投一次。
        return outbox.withLock { sendLocked(envelope) }
    }

    private fun sendLocked(envelope: ConversationMessageEnvelope): String {
        val targetId = envelope.targetConversationId
        when (val existing = outbox.find(envelope.messageId)) {
            is ConversationOutboxRead.Unavailable -> return storeUnavailable(existing.reason)
            is ConversationOutboxRead.Known -> existing.value?.let { stored ->
                if (!stored.matches(envelope)) {
                    return error(
                        "MESSAGE_ID_CONFLICT",
                        "同一 message_id 已用于另一来源/目标/正文，未重复投递；请换新的 message_id",
                    )
                }
                return duplicate(stored)
            }
        }
        val target = host.conversation(targetId)
        val state = if (target != null) host.runState(targetId) else ConversationTargetRunState(ConversationTargetState.IDLE)
        val outcome = when (val stored = outbox.outcome(targetId)) {
            is ConversationOutboxRead.Unavailable -> return storeUnavailable(stored.reason)
            is ConversationOutboxRead.Known -> stored.value?.outcome
        }
        val decision = ConversationDeliveryPolicy.decide(
            sourceConversationId = sourceConversationId,
            targetConversationId = targetId,
            targetExists = target != null,
            target = state,
            lastOutcome = outcome,
            targetReadOnly = target?.readOnly == true,
        )
        return when (decision) {
            is ConversationDelivery.Reject -> error(decision.code, decision.message)
            is ConversationDelivery.QueueOnly -> queue(envelope, decision.reason)
            is ConversationDelivery.IntoRun -> {
                when (val reserved = reserve(envelope)) {
                    is ConversationOutboxWrite.Stored -> Unit
                    is ConversationOutboxWrite.Rejected -> return error(reserved.code, reserved.message)
                    is ConversationOutboxWrite.Failed -> return error(OUTCOME_UNKNOWN, unknownWriteMessage(reserved.message))
                }
                val delivered = runCatching { host.deliverIntoRun(decision.runId, envelope) }.getOrDefault(false)
                if (delivered) {
                    complete(envelope, DELIVERY_IN_RUN, decision.runId)
                } else {
                    // 检查与投递之间 run 已结束：按空闲重新判定，绝不谎称已投递。
                    val fallback = ConversationDeliveryPolicy.decide(
                        sourceConversationId = sourceConversationId,
                        targetConversationId = targetId,
                        targetExists = target != null,
                        target = ConversationTargetRunState(ConversationTargetState.IDLE),
                        lastOutcome = outcome,
                        targetReadOnly = target?.readOnly == true,
                    )
                    when (fallback) {
                        is ConversationDelivery.StartRun -> start(target, envelope)
                        is ConversationDelivery.QueueOnly -> revertToQueue(envelope, fallback.reason)
                        is ConversationDelivery.Reject -> {
                            revertToQueue(envelope, ConversationDeliveryPolicy.TARGET_RUN_UNAVAILABLE)
                        }
                        is ConversationDelivery.IntoRun ->
                            revertToQueue(envelope, ConversationDeliveryPolicy.TARGET_RUN_UNAVAILABLE)
                    }
                }
            }
            is ConversationDelivery.StartRun -> start(target, envelope)
        }
    }

    /** 目标会话开始新一轮运行时补投排队消息；返回实际投递条数。 */
    fun deliverQueued(targetConversationId: String, runId: String): Int {
        if (!allowSend) return 0
        return outbox.withLock {
            val pending = when (val stored = outbox.pendingFor(targetConversationId)) {
                is ConversationOutboxRead.Unavailable -> return@withLock 0
                is ConversationOutboxRead.Known -> stored.value
            }
            var deliveredCount = 0
            for (entry in pending) {
                // 先持久化 SENDING：投递后写状态失败时保留不确定标记，不会被再次自动投递。
                val reserved = entry.copy(state = ConversationDeliveryState.SENDING, delivery = "", note = SENDING_NOTE)
                if (outbox.update(reserved) !is ConversationOutboxWrite.Stored) break
                val delivered = runCatching { host.deliverIntoRun(runId, entry.envelope()) }.getOrDefault(false)
                if (!delivered) {
                    outbox.update(entry)
                    break
                }
                deliveredCount += 1
                val done = entry.copy(
                    state = ConversationDeliveryState.DELIVERED,
                    delivery = DELIVERY_IN_RUN,
                    runId = runId,
                    note = DELIVERED_NOTE_IN_RUN,
                )
                if (outbox.update(done) !is ConversationOutboxWrite.Stored) {
                    // 保持 SENDING：结果不确定，不删记录、不自动重投。
                    break
                }
            }
            deliveredCount
        }
    }

    private fun start(target: ConversationSnapshot?, envelope: ConversationMessageEnvelope): String {
        if (target == null) {
            return error(ConversationDeliveryPolicy.UNKNOWN_CONVERSATION, "目标会话不存在，请重新获取会话列表")
        }
        val reserved = reserve(envelope)
        when (reserved) {
            is ConversationOutboxWrite.Stored -> Unit
            is ConversationOutboxWrite.Rejected -> return error(reserved.code, reserved.message)
            is ConversationOutboxWrite.Failed -> return error(OUTCOME_UNKNOWN, unknownWriteMessage(reserved.message))
        }
        return when (val started = host.startRunWithMessage(target, envelope)) {
            is ConversationStartResult.Started -> complete(envelope, DELIVERY_NEW_RUN, started.runId)
            is ConversationStartResult.Rejected -> revertToQueue(
                envelope,
                "CONVERSATION_START_REJECTED",
                detail = started.message,
            )
        }
    }

    /** 持久化预留（SENDING）后才允许真实投递；写盘失败直接拒绝，不产生任何投递。 */
    private fun reserve(envelope: ConversationMessageEnvelope): ConversationOutboxWrite =
        outbox.record(entryFor(envelope, ConversationDeliveryState.SENDING, "", "", SENDING_NOTE))

    private fun complete(envelope: ConversationMessageEnvelope, delivery: String, runId: String): String {
        val entry = entryFor(
            envelope = envelope,
            state = ConversationDeliveryState.DELIVERED,
            delivery = delivery,
            runId = runId,
            note = if (delivery == DELIVERY_IN_RUN) DELIVERED_NOTE_IN_RUN else DELIVERED_NOTE_NEW_RUN,
        )
        return when (val updated = outbox.update(entry)) {
            is ConversationOutboxWrite.Stored -> JSONObject()
                .put("ok", true)
                .put("tool", TOOL_SEND)
                .put("message_id", envelope.messageId)
                .put("source_conversation_id", sourceConversationId)
                .put("target_conversation_id", envelope.targetConversationId)
                .put("delivery", delivery)
                .put("run_id", runId)
                .put("duplicate", false)
                .put("queued", false)
                .put("receipt_persisted", true)
                .put("outcome_unknown", false)
                .put("note", (if (delivery == DELIVERY_IN_RUN) DELIVERED_NOTE_IN_RUN else DELIVERED_NOTE_NEW_RUN) +
                    "；若需要对方回应，不要用同一 message_id 反复发送")
                .toString()
            is ConversationOutboxWrite.Rejected -> outcomeUnknown(
                envelope, delivery, runId, updated.message, receiptStatusUnknown = false,
            )
            is ConversationOutboxWrite.Failed -> outcomeUnknown(
                envelope, delivery, runId, updated.message, receiptStatusUnknown = true,
            )
        }
    }

    /**
     * 投递动作已经发生，但状态没写进去：保留 SENDING 记录，明确报告 outcome_unknown，
     * 不删除待投记录，也不在同一 message_id 上自动重投。
     */
    private fun outcomeUnknown(
        envelope: ConversationMessageEnvelope,
        delivery: String,
        runId: String,
        reason: String,
        receiptStatusUnknown: Boolean,
    ): String = JSONObject()
        .put("ok", true)
        .put("tool", TOOL_SEND)
        .put("message_id", envelope.messageId)
        .put("source_conversation_id", sourceConversationId)
        .put("target_conversation_id", envelope.targetConversationId)
        .put("delivery", delivery)
        .put("run_id", runId)
        .put("duplicate", false)
        .put("queued", false)
        .put("receipt_persisted", false)
        .put("outcome_unknown", true)
        .put("note", "投递动作已发生（$delivery），但本地状态写入失败（$reason）" +
            if (receiptStatusUnknown) "；本条不会被自动重投，请勿用同一 message_id 重发" else "")
        .toString()

    private fun revertToQueue(
        envelope: ConversationMessageEnvelope,
        reason: String,
        detail: String = "",
    ): String {
        val note = ConversationDeliveryPolicy.queueReasonText(reason) +
            detail.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
        val entry = entryFor(envelope, ConversationDeliveryState.QUEUED, DELIVERY_QUEUED, "", note)
        return when (val updated = outbox.update(entry)) {
            is ConversationOutboxWrite.Stored -> queuedJson(envelope, note)
            is ConversationOutboxWrite.Rejected -> error(
                OUTCOME_UNKNOWN,
                "消息未送达，且投递记录状态未更新（${updated.message}）；请使用新的 message_id 重试",
            )
            is ConversationOutboxWrite.Failed -> error(
                OUTCOME_UNKNOWN,
                "消息未送达，且投递记录状态未写入（${updated.message}）；本条不会被自动重投，请使用新的 message_id",
            )
        }
    }

    private fun queue(envelope: ConversationMessageEnvelope, reason: String): String {
        val note = ConversationDeliveryPolicy.queueReasonText(reason)
        val entry = entryFor(envelope, ConversationDeliveryState.QUEUED, DELIVERY_QUEUED, "", note)
        return when (val written = outbox.record(entry)) {
            is ConversationOutboxWrite.Stored -> queuedJson(envelope, note)
            is ConversationOutboxWrite.Rejected -> error(written.code, written.message)
            is ConversationOutboxWrite.Failed -> error(OUTCOME_UNKNOWN, unknownWriteMessage(written.message))
        }
    }

    private fun queuedJson(envelope: ConversationMessageEnvelope, note: String): String = JSONObject()
        .put("ok", true)
        .put("tool", TOOL_SEND)
        .put("message_id", envelope.messageId)
        .put("source_conversation_id", sourceConversationId)
        .put("target_conversation_id", envelope.targetConversationId)
        .put("delivery", DELIVERY_QUEUED)
        .put("run_id", JSONObject.NULL)
        .put("duplicate", false)
        .put("queued", true)
        .put("receipt_persisted", true)
        .put("outcome_unknown", false)
        .put("note", "$note；不代表已投递，也不代表对方已阅读")
        .toString()

    private fun duplicate(entry: ConversationOutboxEntry): String {
        val uncertain = entry.state == ConversationDeliveryState.SENDING
        val delivery = if (uncertain) OUTCOME_UNKNOWN_DELIVERY else entry.delivery
        return JSONObject()
            .put("ok", true)
            .put("tool", TOOL_SEND)
            .put("message_id", entry.messageId)
            .put("source_conversation_id", entry.sourceConversationId)
            .put("target_conversation_id", entry.targetConversationId)
            .put("delivery", delivery)
            .put("run_id", entry.runId.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("duplicate", true)
            .put("queued", entry.state == ConversationDeliveryState.QUEUED)
            .put("receipt_persisted", true)
            .put("outcome_unknown", uncertain)
            .put("note", if (uncertain) {
                "同一 message_id 的上一次投递结果未确认；为避免重复投递不会自动重试，" +
                    "请确认对方是否收到后再决定是否用新的 message_id 发送"
            } else {
                "同一 message_id 已处理过，本次未重复投递；delivery 是第一次的真实结果，不代表对方已阅读"
            })
            .toString()
    }

    private fun entryFor(
        envelope: ConversationMessageEnvelope,
        state: ConversationDeliveryState,
        delivery: String,
        runId: String,
        note: String,
    ) = ConversationOutboxEntry(
        messageId = envelope.messageId,
        sourceConversationId = envelope.sourceConversationId,
        sourceTitle = envelope.sourceTitle,
        sourceRunId = envelope.sourceRunId,
        targetConversationId = envelope.targetConversationId,
        text = envelope.text,
        createdAt = envelope.createdAt,
        state = state,
        delivery = delivery,
        runId = runId,
        note = note,
    )

    private fun ConversationOutboxEntry.matches(envelope: ConversationMessageEnvelope): Boolean =
        sourceConversationId == envelope.sourceConversationId &&
            targetConversationId == envelope.targetConversationId &&
            text == envelope.text

    private fun storeUnavailable(reason: String): String =
        error("CONVERSATION_OUTBOX_UNAVAILABLE", "跨会话投递记录不可用（$reason），已停止发送与自动唤醒；请检查该记录文件后重试")

    private fun unknownWriteMessage(reason: String): String =
        "投递状态未写入（$reason）；本条不会被自动重投，请使用新的 message_id 重试"

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("tool", TOOL_SEND)
        .put("code", code)
        .put("message", message)
        .toString()

    companion object {
        const val TOOL_LIST = "conversation_list"
        const val TOOL_SEND = "conversation_send"
        const val DELIVERY_IN_RUN = "accepted_in_run"
        const val DELIVERY_NEW_RUN = "started_new_run"
        const val DELIVERY_QUEUED = "queued"
        const val OUTCOME_UNKNOWN = "CONVERSATION_OUTCOME_UNKNOWN"
        const val OUTCOME_UNKNOWN_DELIVERY = "outcome_unknown"
        const val MAX_LIST = 30

        private const val SENDING_NOTE = "投递进行中；若进程中断，本条不会被自动重投"
        private const val DELIVERED_NOTE_IN_RUN =
            "已排入目标会话正在运行的任务，将在其完整回合结束后处理；不代表对方已阅读"
        private const val DELIVERED_NOTE_NEW_RUN =
            "已在目标会话开启新的运行；目标尚未处理该消息，不代表已阅读"
    }
}
