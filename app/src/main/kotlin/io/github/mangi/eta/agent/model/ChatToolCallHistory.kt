package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Chat 请求副本中的 ID 修复不回写持久历史，只接受完整且无歧义的工具交换。 */
internal object ChatToolCallHistory {
    fun referencedIds(messages: JSONArray): Set<String> = buildSet {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "assistant" -> {
                    val calls = message.optJSONArray("tool_calls") ?: continue
                    for (callIndex in 0 until calls.length()) {
                        calls.optJSONObject(callIndex)?.nonBlankString("id")?.let(::add)
                    }
                }
                "tool" -> message.nonBlankString("tool_call_id")?.let(::add)
            }
        }
    }

    fun repairCompleteBatches(messages: JSONArray) {
        val reservedIds = referencedIds(messages).toMutableSet()
        val seenCallIds = mutableSetOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            val pairs = completeBatch(messages, index, calls)
            pairs?.forEachIndexed { callIndex, pair ->
                if (pair.id in seenCallIds) {
                    val base = "call_eta_history_${index}_$callIndex"
                    var replacement = base
                    var suffix = 1
                    while (!reservedIds.add(replacement)) replacement = "${base}_${suffix++}"
                    pair.call.put("id", replacement)
                    pair.result.put("tool_call_id", replacement)
                }
            }
            // 歧义批次保持原样，其已有 ID 仍不能与后续可修复批次重用。
            for (callIndex in 0 until calls.length()) {
                calls.optJSONObject(callIndex)?.nonBlankString("id")?.let(seenCallIds::add)
            }
        }
    }

    /** Check the final Chat Completions wire messages, not just stored DTOs.
     * Never guess tool results or expose prompt/result text in diagnostic errors.
     */
    fun validateCompleteBatches(messages: JSONArray) {
        var outstanding: MutableSet<String>? = null
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
                ?: throw malformed("invalid_message")
            if (message.optString("role") == "tool") {
                val id = message.nonBlankString("tool_call_id")
                val pending = outstanding
                if (id == null || pending == null || !pending.remove(id)) {
                    throw malformed("orphan_or_duplicate_tool_result")
                }
                continue
            }
            if (outstanding?.isNotEmpty() == true) throw malformed("missing_tool_result")
            outstanding = null
            if (message.optString("role") != "assistant" || !message.has("tool_calls")) continue
            val calls = message.optJSONArray("tool_calls")
                ?: throw malformed("invalid_tool_calls")
            if (calls.length() == 0) continue
            val expected = linkedSetOf<String>()
            for (callIndex in 0 until calls.length()) {
                val id = calls.optJSONObject(callIndex)?.nonBlankString("id")
                if (id == null || !expected.add(id)) throw malformed("invalid_or_duplicate_tool_call_id")
            }
            outstanding = expected
        }
        if (outstanding?.isNotEmpty() == true) throw malformed("missing_tool_result")
    }

    private fun malformed(reason: String): AgentModelFailure =
        AgentModelFailure(
            "INVALID_TOOL_HISTORY", false,
            "请求历史中的工具调用与响应不匹配（$reason）；本次请求已在本地阻止，未重放工具或发送至服务商。",
        )

    private fun completeBatch(
        messages: JSONArray,
        assistantIndex: Int,
        calls: JSONArray,
    ): List<CallResultPair>? {
        if (calls.length() == 0) return null
        val callsById = linkedMapOf<String, JSONObject>()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: return null
            val id = call.nonBlankString("id") ?: return null
            if (callsById.put(id, call) != null) return null
        }
        val resultsById = mutableMapOf<String, JSONObject>()
        var index = assistantIndex + 1
        while (index < messages.length()) {
            val result = messages.optJSONObject(index) ?: break
            if (result.optString("role") != "tool") break
            val id = result.nonBlankString("tool_call_id") ?: return null
            if (id !in callsById || resultsById.put(id, result) != null) return null
            index++
        }
        if (callsById.keys != resultsById.keys) return null
        return callsById.map { (id, call) -> CallResultPair(id, call, resultsById.getValue(id)) }
    }

    private fun JSONObject.nonBlankString(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private data class CallResultPair(
        val id: String,
        val call: JSONObject,
        val result: JSONObject,
    )
}
