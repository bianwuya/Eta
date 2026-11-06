package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
import io.github.mangi.eta.data.db.RuntimeRunDao
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Full reports in the existing chunk store, separate from bounded UI previews. */
internal class AgentSubagentCheckpoint(
    context: Context,
    private val runId: String,
    private val parentCancelled: () -> Boolean = { false },
) : YieldableSubagents.Persistence {
    private val db = EtaDatabase.get(context.applicationContext)
    private var stopped = false

    @Synchronized
    override fun reserveGroupMember(taskId: String, toolCallId: String, round: Int, prompt: String, groupKey: String) {
        if (stopped || parentCancelled()) throw AgentRunCancelledException()
        update { records ->
            val group = group(records, groupKey) ?: JSONObject().put("kind", "group").put("group_key", groupKey)
                .put("state", "open").also { records.put(it) }
            check(group.getString("state") == "open") { "组已封口，未启动子任务" }
            check(tasks(records).count { it.optString("group_key") == groupKey } < SubagentTaskGroup.MAX_MEMBERS)
            check(find(records, taskId) == null)
            records.put(JSONObject().put("task_id", taskId).put("tool_call_id", toolCallId).put("round", round)
                .put("prompt", prompt).put("group_key", groupKey).put("state", "reserved"))
        }
    }

    @Synchronized
    override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) {
        if (stopped || parentCancelled()) throw AgentRunCancelledException()
        update { records ->
            val task = find(records, taskId) ?: JSONObject().put("task_id", taskId).also { records.put(it) }
            check(task.optString("state") in setOf("", "reserved"))
            task.put("subagent_id", subagentId).put("tool_call_id", toolCallId).put("round", round)
                .put("accepted", accepted).put("prompt", prompt).put("state", "running")
        }
    }

    override fun finished(taskId: String, report: String): Boolean =
        finished(taskId, report, SubagentTaskResult.fromEnvelope(null))

    override fun finished(taskId: String, report: String, result: SubagentTaskResult): Boolean =
        finished(taskId, report, result, result.status == SubagentTaskResult.Status.COMPLETED, "completed")

    @Synchronized
    override fun finished(taskId: String, report: String, result: SubagentTaskResult, succeeded: Boolean, engineStatus: String): Boolean {
        // Stop sets the controller flag before it cancels children. This guard closes the gap
        // before interrupted() reaches the database; a completed record wins only before stop.
        if (stopped || parentCancelled()) return false
        var committed = false
        update { tasks ->
            val task = find(tasks, taskId) ?: error("子代理启动记录不存在")
            if (task.optString("state") in setOf("running", "reserved")) {
                task.put("state", "finished").put("report", report)
                    .put("task_status", result.status.wire).put("result", result.json())
                    .put("succeeded", succeeded).put("engine_status", engineStatus)
                committed = true
                refreshGroups(tasks)
            }
        }
        return committed
    }

    @Synchronized
    override fun sealGroups(keys: Collection<String>) {
        if (stopped || parentCancelled()) throw AgentRunCancelledException()
        update { records ->
            val selected = keys.map { group(records, it) ?: error("子代理组不存在") }
            selected.forEach { if (it.optString("state") == "open") it.put("state", "sealed") }
            refreshGroups(records)
        }
    }

    @Synchronized
    override fun consumed(messageIds: Collection<String>) {
        if (messageIds.none { it.startsWith("subagent-report-group-") }) return
        update { records ->
            groups(records).filter { SubagentTaskGroup.messageId(runId, it.getString("group_key")) in messageIds }
                .forEach { check(it.optString("state") in setOf("ready", "delivered")); it.put("state", "delivered") }
        }
    }

    @Synchronized
    override fun interrupted() {
        stopped = true
        update(::interrupt)
    }

    private fun update(change: (JSONArray) -> Unit) = runBlocking(Dispatchers.IO) {
        db.withTransaction {
            val dao = db.runtimeRunDao()
            val tasks = read(dao, runId)
            change(tasks)
            write(dao, runId, tasks)
        }
    }

    companion object {
        private const val TABLE = "runtime_subagent_tasks_v1"
        private const val FIELD = "tasks"
        private fun tasks(records: JSONArray): List<JSONObject> =
            (0 until records.length()).map(records::getJSONObject).filter { it.optString("kind") != "group" }
        private fun groups(records: JSONArray): List<JSONObject> =
            (0 until records.length()).map(records::getJSONObject).filter { it.optString("kind") == "group" }
        private fun group(records: JSONArray, key: String): JSONObject? =
            groups(records).firstOrNull { it.optString("group_key") == key }
        private fun find(records: JSONArray, id: String): JSONObject? =
            tasks(records).firstOrNull { it.optString("task_id") == id }

        private fun refreshGroups(records: JSONArray) {
            groups(records).filter { it.optString("state") == "sealed" }.forEach { group ->
                val members = tasks(records).filter { it.optString("group_key") == group.getString("group_key") }
                if (members.isNotEmpty() && members.all { it.optString("state") == "finished" }) group.put("state", "ready")
            }
        }

        private fun interrupt(records: JSONArray) {
            tasks(records).filter { it.optString("state") in setOf("running", "reserved", "interrupted") }.forEach { task ->
                val result = SubagentTaskResult.interrupted(INTERRUPTED_MESSAGE)
                task.put("state", "finished").put("engine_status", "interrupted").put("succeeded", false)
                    .put("task_status", result.status.wire).put("result", result.json())
                    .put("report", interruptedReport(task))
            }
            // Lost/stopped parent has no future membership; never automatically replay child effects.
            groups(records).filter { it.optString("state") == "open" }.forEach { it.put("state", "sealed") }
            refreshGroups(records)
        }

        private const val INTERRUPTED_MESSAGE = "子任务因停止或进程退出而中断，执行状态未知；请先核实，不自动重跑。"
        private fun interruptedReport(task: JSONObject): String = YieldableSubagents.report(task.getString("task_id"),
            task.optString("prompt"), SubagentTaskResult.interrupted(INTERRUPTED_MESSAGE).attach(JSONObject()
                .put("ok", false).put("status", "interrupted").put("subagent_id", task.optString("subagent_id"))
                .put("message", INTERRUPTED_MESSAGE)).toString())

        private suspend fun read(dao: RuntimeRunDao, runId: String): JSONArray {
            val raw = dao.textChunks(TABLE, runId, FIELD, Int.MAX_VALUE, 0).joinToString("") { it.content }
            return if (raw.isEmpty()) JSONArray() else JSONArray(raw)
        }

        private suspend fun write(dao: RuntimeRunDao, runId: String, records: JSONArray) {
            // Runtime acknowledgement removes the run checkpoint. Retain grouped originals by
            // invocation ID in the existing chunk table so the bounded report's refs stay valid.
            for (task in tasks(records).filter { it.has("group_key") && it.has("report") }) {
                val taskId = task.getString("task_id")
                if (dao.textChunks(SubagentTaskGroup.REPORT_TABLE, taskId, "report", 1, 0).isEmpty()) {
                    putText(dao, SubagentTaskGroup.REPORT_TABLE, taskId, "report", task.getString("report"))
                    putText(dao, SubagentTaskGroup.REPORT_TABLE, taskId, "metadata", JSONObject()
                        .put("parent_run_id", runId).put("task_id", taskId).put("group_key", task.getString("group_key"))
                        .put("tool_call_id", task.optString("tool_call_id")).put("subagent_id", task.optString("subagent_id"))
                        .put("round", task.optInt("round")).put("result", task.optJSONObject("result"))
                        .put("engine_status", task.optString("engine_status")).put("succeeded", task.optBoolean("succeeded")).toString())
                }
            }
            putText(dao, TABLE, runId, FIELD, records.toString())
        }

        private suspend fun putText(dao: RuntimeRunDao, table: String, owner: String, field: String, text: String) {
            dao.deleteTextChunks(table, owner, field)
            var start = 0
            var index = 0
            while (start < text.length) {
                var end = minOf(start + 16_384, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                dao.insertTextChunk(io.github.mangi.eta.data.db.AgentTextChunkEntity(table, owner, field, index++, text.substring(start, end)))
                start = end
            }
        }

        /** finalize=false is a read-only preview: listing checkpoints cannot stop an active runtime. */
        fun recover(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>,
            finalize: Boolean = true): List<AgentModelClient.ConversationMessage> =
            runBlocking(Dispatchers.IO) {
                val db = EtaDatabase.get(context.applicationContext)
                db.withTransaction {
                    val dao = db.runtimeRunDao()
                    val records = read(dao, runId)
                    val before = records.toString()
                    if (finalize) interrupt(records)
                    val accepted = buildMap {
                        for (task in tasks(records)) {
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
                    val recovered = completed + buildList {
                        for (index in 0 until records.length()) {
                            val record = records.getJSONObject(index)
                            if (record.optString("kind") == "group") {
                                // Active checkpoint listings are not a natural-wait or stop boundary.
                                // Never expose hypothetical interrupted group output as durable delivery.
                                if (record.optString("state") !in setOf("ready", "delivered")) continue
                                val key = record.getString("group_key")
                                val messageId = SubagentTaskGroup.messageId(runId, key)
                                if (messageId in known) {
                                    record.put("state", "delivered")
                                    continue
                                }
                                val members = tasks(records).filter { it.optString("group_key") == key }.map { task ->
                                    val result = SubagentTaskResult.fromEnvelope(JSONObject().put("ok", true)
                                        .put("task_status", task.optString("task_status")).put("result", task.optJSONObject("result")))
                                    SubagentTaskGroup.Member(task.getString("task_id"), task.optString("tool_call_id"),
                                        task.optString("subagent_id"), result,
                                        task.optBoolean("succeeded") && result.status == SubagentTaskResult.Status.COMPLETED,
                                        task.optString("engine_status", "completed"))
                                }
                                if (members.isNotEmpty()) add(AgentModelClient.ConversationMessage(messageId = messageId,
                                    role = "assistant", content = SubagentTaskGroup.report(runId, key, members)))
                            } else if (!record.has("group_key")) {
                                val messageId = "subagent-report-${record.getString("task_id")}"
                                if (messageId !in known) add(AgentModelClient.ConversationMessage(messageId = messageId,
                                    role = "assistant", content = record.optString("report").ifEmpty { interruptedReport(record) }))
                            }
                        }
                    }
                    // A missing transcript ID retries the same report ID, never a new child invocation.
                    if (finalize && records.toString() != before) write(dao, runId, records)
                    recovered
                }
            }

        fun remove(context: Context, runId: String) = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().deleteLocalTextOwner(TABLE, runId)
        }
    }
}
