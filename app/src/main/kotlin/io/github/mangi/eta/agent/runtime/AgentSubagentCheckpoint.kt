package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Full reports in the existing chunk store, separate from bounded UI previews. */
internal class AgentSubagentCheckpoint(context: Context, private val runId: String) : YieldableSubagents.Persistence {
    private val db = EtaDatabase.get(context.applicationContext)
    private var stopped = false

    @Synchronized
    override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) {
        check(!stopped) { "运行已停止" }
        update { tasks -> tasks.put(JSONObject().put("task_id", taskId).put("subagent_id", subagentId)
            .put("tool_call_id", toolCallId).put("round", round).put("accepted", accepted)
            .put("prompt", prompt).put("state", "running")) }
    }

    @Synchronized
    override fun finished(taskId: String, report: String) {
        if (stopped) return
        update { tasks ->
            val task = find(tasks, taskId) ?: error("子代理启动记录不存在")
            if (task.optString("state") == "running") task.put("state", "finished").put("report", report)
        }
    }

    @Synchronized
    override fun interrupted() {
        stopped = true
        update { tasks ->
            for (index in 0 until tasks.length()) {
                val task = tasks.getJSONObject(index)
                if (task.optString("state") == "running") task.put("state", "interrupted")
            }
        }
    }

    private fun update(change: (JSONArray) -> Unit) = runBlocking(Dispatchers.IO) {
        db.withTransaction {
            val dao = db.runtimeRunDao()
            val raw = dao.textChunks(TABLE, runId, FIELD, Int.MAX_VALUE, 0).joinToString("") { it.content }
            val tasks = if (raw.isEmpty()) JSONArray() else JSONArray(raw)
            change(tasks)
            dao.deleteTextChunks(TABLE, runId, FIELD)
            val text = tasks.toString()
            var start = 0
            var index = 0
            while (start < text.length) {
                var end = minOf(start + 16_384, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                dao.insertTextChunk(io.github.mangi.eta.data.db.AgentTextChunkEntity(TABLE, runId, FIELD, index++, text.substring(start, end)))
                start = end
            }
        }
    }

    companion object {
        private const val TABLE = "runtime_subagent_tasks_v1"
        private const val FIELD = "tasks"
        private fun find(tasks: JSONArray, id: String): JSONObject? =
            (0 until tasks.length()).map(tasks::getJSONObject).firstOrNull { it.optString("task_id") == id }

        fun recover(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> =
            runBlocking(Dispatchers.IO) {
                val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
                val raw = dao.textChunks(TABLE, runId, FIELD, Int.MAX_VALUE, 0).joinToString("") { it.content }
                val tasks = if (raw.isEmpty()) JSONArray() else JSONArray(raw)
                val accepted = buildMap {
                    for (index in 0 until tasks.length()) {
                        val task = tasks.getJSONObject(index)
                        val callId = task.optString("tool_call_id")
                        val content = task.optString("accepted")
                        val round = task.optInt("round")
                        if (callId.isNotBlank() && content.isNotBlank() && round > 0) {
                            put("assistant-$runId-$round" to callId, content)
                        }
                    }
                }
                val completed = io.github.mangi.eta.agent.model.AgentToolBatchRecovery.completeInterrupted(transcript, accepted)
                val known = completed.mapTo(mutableSetOf()) { it.messageId }
                completed + buildList {
                    for (index in 0 until tasks.length()) {
                        val task = tasks.getJSONObject(index)
                        val id = task.getString("task_id")
                        val messageId = "subagent-report-$id"
                        if (messageId in known) continue
                        val content = if (task.optString("state") == "finished") task.getString("report") else
                            YieldableSubagents.report(id, task.optString("prompt"), JSONObject()
                                .put("ok", false).put("status", "interrupted")
                                .put("subagent_id", task.optString("subagent_id"))
                                .put("message", "子任务因停止或进程退出而中断，执行状态未知；请先核实，不自动重跑。").toString())
                        add(AgentModelClient.ConversationMessage(messageId = messageId, role = "assistant", content = content))
                    }
                }
            }

        fun remove(context: Context, runId: String) = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().deleteLocalTextOwner(TABLE, runId)
        }
    }
}
