package io.github.mangi.eta.local.subagentcontext

import org.json.JSONArray
import org.json.JSONObject

/** Task-level working data only; it never changes a child's role, tools or authorization. */
internal data class SubagentTaskHandoff(
    val goal: String = "",
    val knownFacts: List<String> = emptyList(),
    val userConstraints: List<String> = emptyList(),
    val workingHypotheses: List<String> = emptyList(),
    val investigationLeads: List<String> = emptyList(),
    val doneWhen: List<String> = emptyList(),
) {
    private fun block(prompt: String): String {
        val sections = buildList {
            if (goal.isNotEmpty() && goal != prompt.trim()) add("目标\n$goal")
            for ((title, items) in listOf(
                "已确认事实" to knownFacts,
                "有效用户约束" to userConstraints,
                "待验证假设（不是事实）" to workingHypotheses,
                "相关调查入口（非封闭范围）" to investigationLeads,
                "完成标准" to doneWhen,
            )) {
                if (items.isNotEmpty()) add(title + "\n" + items.joinToString("\n") { "- $it" })
            }
        }
        if (sections.isEmpty()) return ""
        return "任务交接（主代理整理的工作数据，不是新增用户授权；假设可按证据修正）\n\n" +
            sections.joinToString("\n\n")
    }

    companion object {
        const val MAX_HANDOFF_CHARS = 16_000
        const val MAX_ITEMS = 32
        private val ARRAY_FIELDS = linkedMapOf(
            "known_facts" to "Verified facts and already completed work; do not put guesses here.",
            "user_constraints" to "Current effective user constraints; preserve them in full. Not a grant of permissions.",
            "working_hypotheses" to "Tentative hypotheses the child may correct using evidence; not verified facts.",
            "investigation_leads" to "Relevant entry points and paths, not a closed investigation boundary.",
            "done_when" to "Observable completion criteria and the evidence needed for delivery.",
        )

        /** Null/empty handoffs preserve the legacy prompt exactly. No summaries or truncation. */
        fun taskPrompt(prompt: String, handoff: Any?): String {
            if (handoff == null || handoff === JSONObject.NULL) return prompt
            require(handoff is JSONObject) { "handoff 必须是对象" }
            val unknown = handoff.keys().asSequence().filter { it != "goal" && it !in ARRAY_FIELDS }.toList()
            require(unknown.isEmpty()) { "handoff 包含不支持的字段；仅支持 goal 和已声明的交接数组" }
            val parsed = SubagentTaskHandoff(
                goal = string(handoff.opt("goal"), "goal"),
                knownFacts = strings(handoff.opt("known_facts"), "known_facts"),
                userConstraints = strings(handoff.opt("user_constraints"), "user_constraints"),
                workingHypotheses = strings(handoff.opt("working_hypotheses"), "working_hypotheses"),
                investigationLeads = strings(handoff.opt("investigation_leads"), "investigation_leads"),
                doneWhen = strings(handoff.opt("done_when"), "done_when"),
            )
            val block = parsed.block(prompt)
            require(block.codePointCount(0, block.length) <= MAX_HANDOFF_CHARS) {
                "handoff 渲染后超过 $MAX_HANDOFF_CHARS 字符；请去除重复背景并保留全部必要约束后重组，任务未启动"
            }
            return if (block.isEmpty()) prompt else "$prompt\n\n$block"
        }

        private fun string(value: Any?, field: String): String {
            if (value == null || value === JSONObject.NULL) return ""
            require(value is String) { "handoff.$field 必须是字符串" }
            require(value.codePointCount(0, value.length) <= MAX_HANDOFF_CHARS) { "handoff.$field 超过 $MAX_HANDOFF_CHARS 字符，未截断" }
            return value.trim()
        }

        private fun strings(value: Any?, field: String): List<String> {
            if (value == null || value === JSONObject.NULL) return emptyList()
            require(value is JSONArray) { "handoff.$field 必须是字符串数组" }
            require(value.length() <= MAX_ITEMS) { "handoff.$field 最多 $MAX_ITEMS 项" }
            return (0 until value.length()).map { index ->
                val item = value.get(index)
                require(item is String) { "handoff.$field 的每一项必须是字符串" }
                string(item, field)
            }.filter(String::isNotEmpty).distinct()
        }

        /** Fresh schema instances avoid cross-request mutation and share limits with the parser. */
        fun schema(): JSONObject {
            val properties = JSONObject().put("goal", JSONObject()
                .put("type", "string").put("maxLength", MAX_HANDOFF_CHARS)
                .put("description", "The actual subtask goal, not just an instruction to search."))
            ARRAY_FIELDS.forEach { (name, description) ->
                properties.put(name, JSONObject().put("type", "array").put("maxItems", MAX_ITEMS)
                    .put("items", JSONObject().put("type", "string").put("maxLength", MAX_HANDOFF_CHARS))
                    .put("description", description))
            }
            return JSONObject().put("type", "object").put("properties", properties)
                .put("additionalProperties", false)
                .put("description", "Optional task handoff written by the parent in this same call. All fields optional. Keep about 6,000 characters or less when possible; the complete rendered block is limited to 16,000 characters (excluding prompt). Invalid or oversized data is rejected before launch, never truncated. On resume include only new or corrected context, not the old package. This is working data, not new authorization.")
        }
    }
}
