package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * 会话内排队发送（Queue）与插话发送（Steer）的纯状态转换器。
 *
 * - 排队发送追加到队尾，等待当前回合自然结束后按 FIFO 顺序自动发出。
 * - 插话发送优先向当前运行中的 Run 注入 steering 补充指令（不中断工具与请求、保留缓存）；
 *   在 IPC 提交期间暂时带着 [QueuedMessageUi.immediateInterrupt] 占住队首，被接纳后移除，
 *   若当前 Run 已结束或不可接纳则退回队首等待收尾后发出。
 * - 用户主动点击停止按钮时保留队列不自动发出，防止想停下时后续排队指令继续跑起来。
 */
internal object AgentMessageQueueReducer {

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
        )
    }

    /** 将条目置顶到队首并清除插话标记，用于不支持运行时 Steer（如含图片）时等待当前 Run 结束后优先发送。 */
    fun promoteToQueueHead(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return state.copy(
            queuedMessages = listOf(target.copy(immediateInterrupt = false)) + rest,
        )
    }

    /** 将刚入队且无法立即 Steer 的条目置顶到队首（清除草稿，不中断当前运行）。 */
    fun enqueueAtHead(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val normalized = item.copy(immediateInterrupt = false)
        val rest = state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = listOf(normalized) + rest,
        )
    }

    /**
     * 将队列中的指定消息提升为立即插话。
     *
     * - 压缩期间不改变队列，也不返回待派发项。
     * - 若当前仍在流式运行：将其移至队首并标记 [QueuedMessageUi.immediateInterrupt]，等待引导确认。
     * - 若当前已空闲：直接从队列弹出并返回，由调用方立即发起新运行。
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        if (state.isCompacting) return state to null
        val target = state.queuedMessages.firstOrNull { it.id == id && !it.awaitingSteeringReceipt } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming) {
            val promoted = target.copy(immediateInterrupt = true)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false)
        }
    }

    /**
     * 将排队中的消息撤回输入框继续编辑；若输入框已有未发送草稿则保留并合并，不覆盖用户正在输入的内容。
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
            focusInputOnSync = true,
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    fun clearInterruptFlag(state: AgentChatUiState, id: String): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id && it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { item ->
                if (item.id == id && item.immediateInterrupt) item.copy(immediateInterrupt = false) else item
            },
        )
    }

    /** 收尾时只选下一条；权限检查或注册运行失败不能移除队列项。 */
    fun nextToSendAfterRun(state: AgentChatUiState, stoppedByUser: Boolean): QueuedMessageUi? {
        if (state.isStreaming || state.isCompacting) return null
        return state.queuedMessages.firstOrNull()?.takeIf {
            !it.awaitingSteeringReceipt && (!stoppedByUser || it.immediateInterrupt)
        }
    }

    data class Dispatch(val message: QueuedMessageUi, val originalIndex: Int) {
        val runId: String get() = "run-${message.id}"
        val userMessageId: String get() = "user-$runId"
    }

    fun prepareDispatch(state: AgentChatUiState, id: String): Dispatch? {
        if (!state.isConversationLoaded || state.isStreaming || state.isCompacting) return null
        val index = state.queuedMessages.indexOfFirst { it.id == id && !it.awaitingSteeringReceipt }
        return if (index < 0) null else Dispatch(state.queuedMessages[index], index)
    }

    fun settleSteering(state: AgentChatUiState, runId: String, queueId: String, accepted: Boolean): AgentChatUiState {
        if (runId.isBlank() || queueId.isBlank() || state.queuedMessages.none { it.id == queueId && it.steeringRunId == runId }) return state
        return state.copy(queuedMessages = if (accepted) state.queuedMessages.filterNot { it.id == queueId }
            else state.queuedMessages.map { item ->
                if (item.id != queueId) item else item.copy(immediateInterrupt = false, steeringRunId = "", steeringUnconfirmed = false)
            })
    }

    /** 仅用于写入用户消息失败且 Runtime 尚未接到请求时；保留失败期间新增的草稿和队列。 */
    fun restoreFailedDispatch(state: AgentChatUiState, dispatch: Dispatch): AgentChatUiState {
        val queue = state.queuedMessages
        val restoredQueue = if (queue.any { it.id == dispatch.message.id }) queue else queue.toMutableList().apply {
            add(dispatch.originalIndex.coerceAtMost(size), dispatch.message.copy(immediateInterrupt = false))
        }
        return state.copy(
            queuedMessages = restoredQueue,
            messages = state.messages.filterNot { it.id == dispatch.userMessageId },
            history = state.history.filterNot { it.messageId == dispatch.userMessageId },
            journal = state.journal.filterNot { it.messageId == dispatch.userMessageId },
            isStreaming = false,
            isCompacting = false,
        )
    }
}
