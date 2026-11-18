package io.github.mangi.eta.agent.voice

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentPendingQueueCodec
import io.github.mangi.eta.ui.model.QueuedMessageUi
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The official wire has no querySteering. Only a positive reply or an exact receipt can settle ambiguity. */
internal class EtaAssistantSteeringCoordinator(
    private val query: suspend () -> AgentRuntimeClient.ActiveRunsQuery,
    private val send: suspend (runId: String, text: String, receiptId: String) -> AgentRuntimeClient.SteerOutcome,
    private val edit: suspend (String, (List<QueuedMessageUi>) -> List<QueuedMessageUi>?) -> Boolean,
) {
    constructor(context: Context, client: AgentRuntimeClient) : this(
        query = { withContext(Dispatchers.IO) { client.queryActiveRuns() } },
        send = { runId, text, id -> withContext(Dispatchers.IO) { client.steerRun(runId, text, id) } },
        edit = { conversationId, transform ->
            withContext(Dispatchers.IO) {
                // The separate owner is compatible with the personal build; chat does not drain it.
                val owner = "voice-steering:$conversationId"
                val db = EtaDatabase.get(context)
                db.withTransaction {
                    val dao = db.conversationDao()
                    val before = AgentPendingQueueCodec.read(dao, owner)
                    val after = transform(before) ?: return@withTransaction false
                    if (after != before) AgentPendingQueueCodec.write(context, dao, owner, after)
                    true
                }
            }
        },
    )

    internal enum class Outcome { Accepted, Rejected, NotReady, PendingReceipt, Unconfirmed, SaveFailed }

    suspend fun submit(
        target: AgentRuntimeWire.ActiveRunSnapshot,
        text: String,
        stillSelected: () -> Boolean,
        onPersisted: () -> Unit,
    ): Outcome {
        if (text.isBlank()) return Outcome.NotReady
        val item = QueuedMessageUi(id = UUID.randomUUID().toString(), content = text,
            steeringRunId = target.runId, steeringUnconfirmed = true)
        var staged = false
        return try {
            if (!stillSelected() || !query().canGuideVoiceTask(target) || !stillSelected()) return Outcome.NotReady
            staged = edit(target.conversationId) { pending ->
                // Never send another instruction while this run has an unresolved receipt.
                if (pending.any { it.steeringRunId == target.runId }) null else pending + item
            }
            if (!staged) return Outcome.PendingReceipt
            val ready = query().canGuideVoiceTask(target)
            if (!stillSelected() || !ready) {
                acknowledge(target, item.id) // Definitely unsent.
                return Outcome.NotReady
            }
            onPersisted()
            when (val outcome = send(target.runId, text, item.id)) {
                is AgentRuntimeClient.SteerOutcome.Accepted -> if (outcome.supplementIndex > 0) {
                    acknowledge(target, item.id)
                    Outcome.Accepted
                } else Outcome.Unconfirmed
                AgentRuntimeClient.SteerOutcome.Rejected -> {
                    acknowledge(target, item.id)
                    Outcome.Rejected
                }
                AgentRuntimeClient.SteerOutcome.Unavailable -> Outcome.Unconfirmed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Before staging there is no send; after staging retain anything not durably settled.
            if (staged) Outcome.Unconfirmed else Outcome.SaveFailed
        }
    }

    suspend fun acknowledge(target: AgentRuntimeWire.ActiveRunSnapshot, receiptId: String) {
        if (target.isVoiceTask() && target.conversationId.isNotBlank() && receiptId.isNotBlank()) {
            edit(target.conversationId) { pending ->
                pending.filterNot { it.id == receiptId && it.steeringRunId == target.runId }
            }
        }
    }
}
