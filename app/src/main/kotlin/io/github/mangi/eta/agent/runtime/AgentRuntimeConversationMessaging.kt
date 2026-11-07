package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.local.conversationcommunication.ConversationMessageEnvelope
import io.github.mangi.eta.local.conversationcommunication.ConversationMessageOutbox
import io.github.mangi.eta.local.conversationcommunication.ConversationMessagingHost
import io.github.mangi.eta.local.conversationcommunication.ConversationMessagingService
import io.github.mangi.eta.local.conversationcommunication.ConversationMessagingTool
import io.github.mangi.eta.local.conversationcommunication.ConversationSnapshot
import io.github.mangi.eta.local.conversationcommunication.ConversationStartResult
import io.github.mangi.eta.local.conversationcommunication.ConversationTargetRunState
import io.github.mangi.eta.local.conversationcommunication.ConversationTargetState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 把 Runtime 的会话索引、活跃 run 与现有发送链路接到跨会话工具上。
 *
 * 边界：
 * - 只对主会话（agent_ui handoff）的 chat run 暴露工具，子代理与语音/外部归档入口不注入；
 * - 闲置会话续发用现有 `startRun` 链路（replyTo 为空，结果经 checkpoint/outbox 由 UI 恢复）；
 * - 由跨会话消息启动的 run 带 origin 标记，构造时 [allowSend] 为 false，不能再发出消息。
 */
internal class AgentRuntimeConversationMessaging(
    private val context: Context,
    private val runs: RuntimeRunRegistry,
    private val runId: String,
    private val conversationId: String,
    private val outbox: ConversationMessageOutbox,
    private val permissions: () -> AgentRuntimePolicy.Permissions,
    private val startRun: (AgentRuntimeWire.RunRequest, ConversationMessageEnvelope) -> String?,
    private val allowSend: Boolean,
) : ConversationMessagingHost {

    val service: ConversationMessagingService by lazy {
        ConversationMessagingService(
            sourceConversationId = conversationId,
            sourceTitle = conversationTitle(conversationId),
            sourceRunId = runId,
            host = this,
            outbox = outbox,
            allowSend = allowSend,
        )
    }

    val tool: AgentModelClient.ToolExecutor by lazy { ConversationMessagingTool(service) }

    override fun conversation(conversationId: String): ConversationSnapshot? {
        val row = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context).conversationDao().conversationMetadataRow(conversationId)
        } ?: return null
        return row.toSnapshot()
    }

    override fun conversations(limit: Int): List<ConversationSnapshot> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context).conversationDao()
            .conversationMetadataPage(limit.coerceIn(1, MAX_LIST), 0)
            .map { it.toSnapshot() }
    }

    override fun runState(conversationId: String): ConversationTargetRunState {
        val active = runs.all().firstOrNull {
            it.conversationId == conversationId && !it.session.isTerminal
        } ?: return ConversationTargetRunState(ConversationTargetState.IDLE)
        val state = if (active.session.controller.isPaused) {
            ConversationTargetState.PAUSED
        } else {
            ConversationTargetState.RUNNING
        }
        return ConversationTargetRunState(state, active.runId)
    }

    override fun deliverIntoRun(runId: String, envelope: ConversationMessageEnvelope): Boolean {
        val run = runs.get(runId) ?: return false
        return run.session.controller.enqueueConversationMessage(
            AgentRunController.ConversationInput(
                messageId = envelope.messageId,
                sourceConversationId = envelope.sourceConversationId,
                sourceTitle = envelope.sourceTitle,
                sourceRunId = envelope.sourceRunId,
                createdAt = envelope.createdAt,
                envelopeText = envelope.render(),
            ),
        )
    }

    override fun startRunWithMessage(
        target: ConversationSnapshot,
        envelope: ConversationMessageEnvelope,
    ): ConversationStartResult {
        // 与 UI 打开会话一致：checkpoint 缺失时回退到消息行，不能把旧会话当空历史。
        val conversation = runBlocking(Dispatchers.IO) {
            AgentConversationStore.loadConversation(context, target.conversationId)
        } ?: return ConversationStartResult.Rejected(
            "CONVERSATION_CONTEXT_UNAVAILABLE",
            "无法读取目标会话上下文，消息只排队，不会自动唤醒",
        )
        val config = runBlocking { RuntimeConfigRepository.runtimeConfigFor(target.modelId) }
            ?: return ConversationStartResult.Rejected(
                "CONVERSATION_MODEL_UNAVAILABLE",
                "目标会话没有可用模型，请先在 Eta 中配置",
            )
        val newRunId = "run-${UUID.randomUUID()}"
        val request = AgentRuntimeWire.RunRequest(
            runId = newRunId,
            // 固定触发提示：跨会话正文只作为运行时数据注入，不作为 user 消息发送。
            prompt = envelope.triggerPrompt(),
            config = AgentRuntimePolicy.constrain(config, permissions()),
            images = emptyList(),
            history = conversation.history,
            handoff = AgentRuntimeWire.EntryHandoff(
                id = newRunId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = AgentUiHandoffPayload(conversationId = target.conversationId).toJson(),
            ),
            // 与 UI 发送一致：同一会话复用同一 model session，保留稳定前缀。
            modelSessionId = target.conversationId,
            origin = AgentRuntimeWire.RUN_ORIGIN_CONVERSATION_MESSAGE,
        )
        val failure = startRun(request, envelope)
        return if (failure == null) {
            ConversationStartResult.Started(newRunId)
        } else {
            ConversationStartResult.Rejected("CONVERSATION_START_REJECTED", failure)
        }
    }

    private fun conversationTitle(conversationId: String): String = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context).conversationDao().conversationMetadataRow(conversationId)?.title.orEmpty()
    }

    private fun io.github.mangi.eta.data.db.ConversationMetadata.toSnapshot(): ConversationSnapshot =
        ConversationSnapshot(
            conversationId = id,
            title = title,
            updatedAt = updatedAt,
            modelId = modelId,
            readOnly = AgentConversationIdentity.isArchivedConversationId(id),
        )

    private companion object {
        const val MAX_LIST = 120
    }
}
