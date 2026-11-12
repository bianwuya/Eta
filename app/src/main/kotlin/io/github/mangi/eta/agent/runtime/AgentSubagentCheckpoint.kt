package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Read-only compatibility for retired acceptance receipts; new runs use official transcripts. */
internal object AgentSubagentCheckpoint {
    fun recover(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>,
        finalize: Boolean = true): List<AgentModelClient.ConversationMessage> = runBlocking(Dispatchers.IO) {
        val raw = EtaDatabase.get(context.applicationContext).runtimeRunDao()
            .textChunks("runtime_subagent_tasks_v1", runId, "tasks", Int.MAX_VALUE, 0).joinToString("") { it.content }
        val records = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        val known = transcript.mapTo(mutableSetOf()) { it.messageId }
        val compatibilityReports = buildList {
            for (i in 0 until records.length()) {
                val task = records.getJSONObject(i)
                if (task.optString("kind") == "group") continue
                val id = task.optString("task_id")
                if (id.isBlank()) continue
                val messageId = "subagent-report-$id"
                if (known.add(messageId)) {
                    val report = task.optString("report").ifBlank {
                        JSONObject().put("ok", false).put("code", "LEGACY_SUBAGENT_OUTCOME_UNKNOWN")
                            .put("message", "旧子任务结果未确认；请核实，不自动重跑。").toString()
                    }
                    add(AgentModelClient.ConversationMessage(role = "assistant", content = report, messageId = messageId))
                }
            }
        }
        AgentToolBatchRecovery.completeInterrupted(if (finalize) interruptRunningReceipts(transcript) else transcript) + compatibilityReports
    }

    /** A process-local worker cannot still be running after its owning Runtime was lost. */
    internal fun interruptRunningReceipts(messages: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> =
        messages.map { message ->
            val receipt = if (message.role == "tool") runCatching { JSONObject(message.content) }.getOrNull() else null
            if (receipt?.optString("code") != "SUBAGENT_RUNNING") message else message.copy(content = receipt
                .put("ok", false).put("code", "SUBAGENT_INTERRUPTED").put("status", "unknown")
                .put("message", "原运行已中断，不能确认该子代理最终结果。请核实保存的子代理记录；不要自动重新派发或重放工具。")
                .toString())
        }
}
