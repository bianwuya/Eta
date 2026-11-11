package io.github.mangi.eta.agent.voice

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentPendingQueueCodec
import io.github.mangi.eta.ui.model.QueuedMessageUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal class EtaAssistantSteeringCoordinator(
    private val context: Context,
    private val runtimeClient: AgentRuntimeClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val databaseProvider: () -> EtaDatabase = { EtaDatabase.get(context) },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private val globalMutex = Mutex()
    }

    fun submitSteering(
        runId: String,
        conversationKey: String,
        prompt: String,
        onPersisted: (() -> Unit)? = null,
        onFailure: (suspend (Throwable) -> Unit)? = null,
        restoreInput: suspend (String) -> Unit,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                steer(runId, conversationKey, prompt, onPersisted, onFailure, restoreInput)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(mainDispatcher) { onFailure?.invoke(failure) }
            }
        }
    }

    suspend fun steer(
        runId: String,
        conversationKey: String,
        prompt: String,
        onPersisted: (() -> Unit)? = null,
        onFailure: (suspend (Throwable) -> Unit)? = null,
        restoreInput: suspend (String) -> Unit,
    ) {
        if (runId.isBlank() || prompt.isBlank()) return
        globalMutex.withLock {
            val conversationId = AgentPendingQueueCodec.voiceOwner(AgentConversationIdentity.archiveConversationId(
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, conversationKey
            ))
            val queueId = UUID.randomUUID().toString()
            val item = QueuedMessageUi(
                id = queueId,
                content = prompt,
                steeringRunId = runId,
                steeringUnconfirmed = false,
            )
            val db = databaseProvider()

            // 1. 发送前调用 AgentPendingQueueCodec 落盘 Room 分块存储，成功后才通知清空输入框
            try {
                withContext(ioDispatcher) {
                    db.withTransaction {
                        val dao = db.conversationDao()
                        val existing = AgentPendingQueueCodec.readOwned(dao, conversationId)
                        val updated = existing + item
                        AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, updated))
                    }
                }
                withContext(mainDispatcher) {
                    onPersisted?.invoke()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(mainDispatcher) {
                    restoreInput(prompt)
                    onFailure?.invoke(failure)
                }
                return@withLock
            }

            // 2. 调用 runtimeClient.steerRun
            val outcome = withContext(ioDispatcher) {
                runtimeClient.steerRun(runId, queueId, prompt)
            }

            when (outcome) {
                AgentRuntimeClient.SteeringOutcome.Accepted -> {
                    // 成功清除队列
                    withContext(ioDispatcher) {
                        db.withTransaction {
                            val dao = db.conversationDao()
                            val current = AgentPendingQueueCodec.read(dao, conversationId)
                            val remaining = current.filterNot { it.id == queueId }
                            AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, remaining))
                        }
                    }
                }
                AgentRuntimeClient.SteeringOutcome.Preparing,
                AgentRuntimeClient.SteeringOutcome.NotAccepted,
                AgentRuntimeClient.SteeringOutcome.InvalidInput,
                AgentRuntimeClient.SteeringOutcome.Unsupported,
                AgentRuntimeClient.SteeringOutcome.Unauthorized,
                AgentRuntimeClient.SteeringOutcome.StorageFailed -> {
                    // 明确拒绝清除队列并恢复输入
                    withContext(ioDispatcher) {
                        db.withTransaction {
                            val dao = db.conversationDao()
                            val current = AgentPendingQueueCodec.read(dao, conversationId)
                            val remaining = current.filterNot { it.id == queueId }
                            AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, remaining))
                        }
                    }
                    withContext(mainDispatcher) {
                        restoreInput(prompt)
                    }
                }
                AgentRuntimeClient.SteeringOutcome.Unavailable -> {
                    // 超时置 steeringUnconfirmed 并调用 querySteering，不自动重发
                    withContext(ioDispatcher) {
                        db.withTransaction {
                            val dao = db.conversationDao()
                            val current = AgentPendingQueueCodec.read(dao, conversationId)
                            val updated = current.map {
                                if (it.id == queueId) it.copy(steeringUnconfirmed = true) else it
                            }
                            AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, updated))
                        }
                    }
                    val status = withContext(ioDispatcher) {
                        runtimeClient.querySteering(runId, queueId)
                    }
                    when (status) {
                        is AgentRuntimeClient.SteeringStatus.Accepted -> {
                            withContext(ioDispatcher) {
                                db.withTransaction {
                                    val dao = db.conversationDao()
                                    val current = AgentPendingQueueCodec.read(dao, conversationId)
                                    val remaining = current.filterNot { it.id == queueId }
                                    AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, remaining))
                                }
                            }
                        }
                        AgentRuntimeClient.SteeringStatus.NotAccepted -> {
                            withContext(ioDispatcher) {
                                db.withTransaction {
                                    val dao = db.conversationDao()
                                    val current = AgentPendingQueueCodec.read(dao, conversationId)
                                    val remaining = current.filterNot { it.id == queueId }
                                    AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, remaining))
                                }
                            }
                            withContext(mainDispatcher) {
                                restoreInput(prompt)
                            }
                        }
                        AgentRuntimeClient.SteeringStatus.Unavailable -> {
                            // 保持未确认标记供后续核验
                        }
                    }
                }
            }
        }
    }

    suspend fun reconcileUnconfirmed(
        runId: String,
        conversationKey: String,
        restoreInput: (suspend (String) -> Unit)? = null,
    ) {
        if (runId.isBlank()) return
        globalMutex.withLock {
            val conversationId = AgentPendingQueueCodec.voiceOwner(AgentConversationIdentity.archiveConversationId(
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, conversationKey
            ))
            val db = databaseProvider()
            val queue = withContext(ioDispatcher) {
                db.withTransaction {
                    val dao = db.conversationDao()
                    AgentPendingQueueCodec.read(dao, conversationId)
                }
            }
            val unconfirmed = queue.filter { it.steeringRunId == runId && it.steeringUnconfirmed }
            if (unconfirmed.isEmpty()) return@withLock

            var remaining = queue
            for (item in unconfirmed) {
                val status = withContext(ioDispatcher) {
                    runtimeClient.querySteering(runId, item.id)
                }
                when (status) {
                    is AgentRuntimeClient.SteeringStatus.Accepted -> {
                        remaining = remaining.filterNot { it.id == item.id }
                    }
                    AgentRuntimeClient.SteeringStatus.NotAccepted -> {
                        // 保留为可编辑草稿，并回调恢复输入，不丢正文
                        if (restoreInput != null) withContext(mainDispatcher) { restoreInput(item.content) }
                        remaining = if (restoreInput != null) remaining.filterNot { it.id == item.id } else remaining.map {
                            if (it.id == item.id) it.copy(steeringRunId = "", steeringUnconfirmed = false) else it
                        }
                    }
                    AgentRuntimeClient.SteeringStatus.Unavailable -> Unit
                }
            }
            if (remaining != queue) {
                withContext(ioDispatcher) {
                    db.withTransaction {
                        val dao = db.conversationDao()
                        val resolved = remaining.associateBy { it.id }
                        val originalIds = queue.mapTo(mutableSetOf()) { it.id }
                        val latest = AgentPendingQueueCodec.read(dao, conversationId)
                        val updated = latest.mapNotNull { if (it.id in originalIds) resolved[it.id] else it }
                        AgentPendingQueueCodec.write(dao, conversationId, AgentPendingQueueCodec.encode(context, updated))
                    }
                }
            }
        }
    }
}
