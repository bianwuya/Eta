package io.github.mangi.eta.local.subagentcontext

import org.json.JSONObject

/** A model's explicit task claim, never an engine lifecycle or independent acceptance. */
internal data class SubagentTaskResult private constructor(
    val status: Status,
    val summary: String,
    val evidence: String = "",
    val remaining: String = "",
    val nextAction: String = "",
    val source: String = "unknown",
    val truncated: Boolean = false,
    val diagnostic: String = "",
) {
    enum class Status(val wire: String) {
        COMPLETED("completed"), PARTIAL("partial"), BLOCKED("blocked"), FAILED("failed"), UNKNOWN("unknown");
    }

    fun json(): JSONObject = JSONObject().put("version", 1).put("status", status.wire)
        .put("summary", summary).put("evidence", evidence).put("remaining", remaining)
        .put("next_action", nextAction).put("source", source).put("truncated", truncated)
        .put("diagnostic", diagnostic)

    fun attach(envelope: JSONObject): JSONObject = envelope.put("task_status", status.wire).put("result", json())

    companion object {
        const val MAX_SUMMARY = 1_200
        const val MAX_EVIDENCE = 3_200
        const val MAX_REMAINING = 2_400
        const val MAX_NEXT_ACTION = 800
        private val fields = listOf("task_status", "summary", "evidence", "remaining", "next_action")
        private val header = Regex("(?m)^(task_status|summary|evidence|remaining|next_action):[ \\t]*")

        val INSTRUCTIONS = "最终回复使用简短稳定的文字报告，不要求JSON。按以下五个英文字段依次各起一行，" +
            "字段内容可换行，字段名只出现一次，不用代码围栏包住报告：\n" +
            "task_status: completed|partial|blocked|failed|unknown（只选一个英文值）\n" +
            "summary: 实际结论或产物，尽量不超过1200字符\n" +
            "evidence: 可核验文件位置、工具结果或来源，尽量不超过3200字符\n" +
            "remaining: 未完成事项与不确定性，尽量不超过2400字符；确实无遗留写 -\n" +
            "next_action: 未完成时给主代理的可执行建议，尽量不超过800字符；无需后续写 -\n" +
            "completed仅表示本次委派完成标准已满足，必须有证据且无未完成事项；" +
            "partial为已有有效成果但未完成，blocked为缺关键前提或决策，failed为任务失败，unknown为无法可靠判断。" +
            "部分完成或阻塞必须明确remaining。模型正常返回不等于任务完成，模型声明不等于本人验收。" +
            "不要为了字段长度删除必要证据或有效约束；完整原文仍保留。"

        fun fromFinal(text: String, completeTurn: Boolean = true): SubagentTaskResult {
            if (!completeTurn) return unknown(text, "incomplete_model_turn")
            val normalized = text.trim().replace("\r\n", "\n")
            val matches = header.findAll(normalized).toList()
            if (matches.size != fields.size || matches.map { it.groupValues[1] } != fields ||
                matches.first().range.first != 0) return unknown(text, "missing_or_ambiguous_fields")
            val values = matches.mapIndexed { index, match ->
                normalized.substring(match.range.last + 1,
                    matches.getOrNull(index + 1)?.range?.first ?: normalized.length).trim()
            }
            val status = Status.entries.firstOrNull { it.wire == values[0] }
                ?: return unknown(text, "invalid_task_status")
            val summary = emptyMarker(values[1])
            val evidence = emptyMarker(values[2])
            val remaining = emptyMarker(values[3])
            val next = emptyMarker(values[4])
            if (summary.isBlank() || (status == Status.COMPLETED && (evidence.isBlank() || remaining.isNotEmpty())) ||
                (status in setOf(Status.PARTIAL, Status.BLOCKED) && remaining.isBlank())) {
                return unknown(text, "inconsistent_task_result")
            }
            return project(status, summary, evidence, remaining, next, "model")
        }

        fun runtimeFailure(message: String): SubagentTaskResult = project(Status.FAILED,
            message, "", "执行异常；已产生的外部结果需核实。", "核实失败原因和已执行操作后决定接手或续接。", "runtime")

        fun interrupted(message: String): SubagentTaskResult = project(Status.UNKNOWN,
            message, "", "任务中断；副作用和完成情况未知。", "先核实执行结果，不自动重跑。", "runtime")

        /** Missing/legacy metadata is unknown; lifecycle completed alone never releases a dependency. */
        fun fromEnvelope(envelope: JSONObject?): SubagentTaskResult {
            if (envelope == null) return unknown("", "legacy_result")
            if (envelope.optString("status") in setOf("cancelled", "interrupted")) {
                return interrupted(envelope.optString("message"))
            }
            if (envelope.opt("ok") == false) return runtimeFailure(envelope.optString("message"))
            val result = envelope.optJSONObject("result") ?: return unknown("", "legacy_result")
            val status = Status.entries.firstOrNull { it.wire == result.opt("status") }
                ?: return unknown("", "invalid_result_status")
            if (result.opt("version") != 1 || envelope.opt("task_status") != status.wire ||
                result.opt("source") !in setOf("model", "runtime", "unknown") ||
                result.opt("truncated") !is Boolean ||
                listOf("summary", "evidence", "remaining", "next_action", "diagnostic").any { result.opt(it) !is String }) {
                return unknown("", "invalid_result_metadata")
            }
            val summary = result.getString("summary")
            val evidence = result.getString("evidence")
            val remaining = result.getString("remaining")
            if (status == Status.COMPLETED && (result.opt("source") != "model" || summary.isBlank() || evidence.isBlank() || remaining.isNotEmpty())) {
                return unknown("", "inconsistent_task_result")
            }
            return project(status, summary, evidence, remaining, result.getString("next_action"),
                result.getString("source"), result.getString("diagnostic"))
                .let { it.copy(truncated = it.truncated || result.getBoolean("truncated")) }
        }

        fun succeeded(envelope: JSONObject?): Boolean = envelope?.opt("ok") == true &&
            envelope.optString("status") == "completed" && fromEnvelope(envelope).status == Status.COMPLETED

        private fun unknown(text: String, reason: String): SubagentTaskResult =
            project(Status.UNKNOWN, text, "", "未取得可信的任务语义状态。", "核对原始报告后决定下一步。", "unknown", reason)

        private fun emptyMarker(text: String): String = if (text == "-") "" else text

        private fun project(status: Status, summary: String, evidence: String, remaining: String,
            next: String, source: String, diagnostic: String = ""): SubagentTaskResult {
            val full = listOf(summary, evidence, remaining, next)
            val limits = listOf(MAX_SUMMARY, MAX_EVIDENCE, MAX_REMAINING, MAX_NEXT_ACTION)
            val bounded = full.mapIndexed { index, value -> takeCodePoints(value, limits[index]) }
            return SubagentTaskResult(status, bounded[0], bounded[1], bounded[2], bounded[3], source,
                bounded != full, diagnostic)
        }

        private fun takeCodePoints(text: String, limit: Int): String =
            if (text.codePointCount(0, text.length) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit))
    }
}
