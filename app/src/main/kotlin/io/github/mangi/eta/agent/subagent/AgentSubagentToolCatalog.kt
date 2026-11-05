package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明给主 Agent 的 `subagent` 与 `subagent_profile` 工具 Schema。
 */
internal object AgentSubagentToolCatalog {
    const val TOOL_NAME = "subagent"
    const val PROFILE_TOOL_NAME = "subagent_profile"
    const val CONTROL_TOOL_NAME = "subagent_control"
    const val REPORT_TOOL_NAME = "subagent_report"

    val names = setOf(TOOL_NAME, PROFILE_TOOL_NAME, CONTROL_TOOL_NAME)

    fun appendSchemas(
        tools: JSONArray,
        profiles: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
    ) {
        tools.put(schema(profiles))
        tools.put(profileToolSchema())
        tools.put(controlSchema())
    }

    fun schema(
        profiles: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
    ): JSONObject {
        val profileLines = profiles.joinToString("\n") { profile ->
            val tag = if (profile.builtin) "" else " [custom]"
            "- ${profile.name}$tag: ${profile.whenToUse} (Tools: ${profile.toolHighlights.joinToString(", ")})"
        }
        val description = buildString {
            append("Start an asynchronous isolated subtask. Returns a task ID and actual state immediately, NOT a completed summary. ")
            append("Up to 16 invocations per parent run (4 executing concurrently; others queue). Create specialized ephemeral subagents without saving templates, ")
            append("or use one of the preset types below. When multiple independent subtasks can run in parallel, issue multiple `subagent` tool calls in the same turn — the runtime executes them concurrently.\n\n")
            append("Use subagent_control to wait (wakes on user input, decisions, a coordination report or termination), query, guide, pause, continue or cancel a specific task. Do independent work while it runs. Ordinary user progress questions do not cancel tasks or broadcast steering. Keep full required constraints and corrections; no fixed report length. Acknowledge/resolve paused tasks and collect results before ending the parent run.\n\n")
            append("Soft budgets: a child pauses at a safe boundary when its wall time, rounds or observed tokens reach the grant window. status reports which dimension hit plus window vs cumulative usage; continue grants one new window and can adjust max_time_seconds/max_rounds/max_tokens in the same call. Coder/engineering children default to 6000000 observed tokens, read-only profiles to 300000; the counter sums each round's input+output (including repeated history), not context size or net billing.\n\n")
            append("Writing the prompt:\n")
            append("- A fresh subagent starts with zero conversation history. State the goal clearly and include any known file paths, time ranges, package names, or constraints.\n")
            append("- Prefer resuming an existing subagent (pass its `resume` id) when continuing earlier work so it retains its prior context.\n")
            append("- For ad-hoc specialized roles (e.g. `bill-analyzer`, `log-auditor`, `schedule-checker`), pass the role name in `subagent_type` and optionally specify `role` or `tools` — it runs immediately as an ephemeral subagent.\n")
            append("- The subagent's output is only returned to you; summarize relevant findings for the user in your own reply.\n\n")
            append("Preset agent types (or pass any custom kebab-case `subagent_type` for an ephemeral subagent):\n")
            append(profileLines)
        }
        val properties = JSONObject()
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 16_000)
                    .put("description", "Complete task instructions and necessary context for the subagent."),
            )
            .put(
                "description",
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 120)
                    .put("description", "Short task label (3-5 words) displayed in the timeline UI."),
            )
            .put(
                "subagent_type",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 48)
                    .put(
                        "description",
                        "Preset type (\"explore\", \"coder\", \"plan\", or a saved custom profile) OR any ad-hoc kebab-case role name (e.g. \"bill-analyzer\", \"apk-inspector\") to spawn an ephemeral specialized subagent on the fly. Defaults to \"explore\" when omitted. Do not set when `resume` is provided.",
                    ),
            )
            .put(
                "role",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 2_000)
                    .put(
                        "description",
                        "Optional system role or persona instructions for an ad-hoc ephemeral subagent (only used for this subagent instance, not saved to disk).",
                    ),
            )
            .put(
                "tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 48)
                    .put(
                        "description",
                        "Optional tool allowlist for an ad-hoc subagent (e.g. [\"summarize_bills\", \"search_bills\"]). Omit to use the preset's tools or all non-GUI tools.",
                    ),
            )
            .put(
                "resume",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 64)
                    .put(
                        "description",
                        "Optional subagent_id from a previous `subagent` result in this conversation to continue its existing context.",
                    ),
            )
        properties.put("max_time_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7200)
            .put("description", "Wall-time soft budget per explicit grant, default 900s; a queued task starts counting when it actually runs. Pause happens at the next complete-turn boundary."))
        properties.put("max_rounds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1000)
            .put("description", "Successful model-round soft budget per grant, default 100."))
        properties.put("max_tokens", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10000000)
            .put("description", "Observed input+output token soft budget per grant. Default by profile: coder/engineering 6000000, read-only profiles 300000." +
                " The counter accumulates every round's input+output, including repeated history, so it is neither the context length nor the post-cache bill." +
                " Missing usage stays unknown, never estimated."))
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("prompt", "description")))
            .put("additionalProperties", false)
        return AgentToolSchema.function(
            name = TOOL_NAME,
            description = description,
            parameters = parameters,
        )
    }

    fun controlSchema(): JSONObject = AgentToolSchema.function(
        name = CONTROL_TOOL_NAME,
        description = "Control only children owned by this parent run. status lists state (including window vs cumulative budget usage and pause_detail); result reads the actual result; wait blocks up to 60s without repeated model calls and wakes on user steering, a decision, a coordination report or terminal event. guide queues complete text for the target's next safe boundary, not immediate understanding; it does not resume a paused task. revise=true advances the goal revision and old-revision results cannot count as its completion. pause is requested until acknowledged at a safe boundary. continue grants one new budget window and may adjust max_time_seconds/max_rounds/max_tokens in the same call (omitted fields keep the current value; invalid values change nothing); text passed to continue is delivered as guidance in that new window. cancel affects only this task and does not roll back executed commands. Running tasks use guide, not concurrent resume. Handle every active task before ending the parent run.",
        parameters = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "result", "wait", "guide", "pause", "continue", "cancel"))))
                .put("task_id", JSONObject().put("type", "string").put("description", "Exact task_id or subagent_id returned by launch. Required for guide/pause/continue/cancel."))
                .put("text", JSONObject().put("type", "string").put("maxLength", 32000)
                    .put("description", "Complete guidance, constraints, corrections and necessary evidence; do not mechanically shorten. For continue it is delivered in the new budget window."))
                .put("revise", JSONObject().put("type", "boolean").put("description", "Advance goal revision only for a material change in objective, not normal progress questions."))
                .put("max_time_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7200)
                    .put("description", "continue only: replace the wall-time budget of the new window; omit to keep the current value."))
                .put("max_rounds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1000)
                    .put("description", "continue only: replace the round budget of the new window; omit to keep the current value."))
                .put("max_tokens", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10000000)
                    .put("description", "continue only: replace the observed input+output token budget of the new window; omit to keep the current value."))
                .put("timeout_ms", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 60000))),
    )

    fun reportSchema(): JSONObject = AgentToolSchema.function(
        name = REPORT_TOOL_NAME,
        description = "Send a stage report to your parent, not the end user. kind=coordination (default, also for legacy calls without kind) is kept in full and wakes a waiting parent without pausing you; use it for key findings, scope expansion, before out-of-scope edits, blockers and interface decisions. kind=final marks the handoff before you finish. kind=progress only updates your task detail and never enters the parent mailbox, so ordinary progress stays quiet; choose it explicitly for routine updates. needs_decision=true always wins over kind and pauses you at a safe complete-turn boundary. Include confirmed facts with evidence, current work, next steps and validation scope; no fixed word count and do not drop necessary instructions/evidence. No hidden chain-of-thought or fabricated progress.",
        parameters = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("text"))).put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 32000))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("coordination", "progress", "final")))
                    .put("description", "coordination (default): key facts the parent must see, wakes it without pausing you. progress: ordinary updates, detail only. final: completed handoff."))
                .put("needs_decision", JSONObject().put("type", "boolean")
                    .put("description", "true when the parent must choose before you continue; overrides kind=progress."))),
    )

    fun profileToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("list", "get", "save", "delete")))
                    .put("description", "Action to perform: list all subagent profiles, get one profile's details, save (create/update) a custom subagent profile in `agents/<name>.md`, or delete a custom profile."),
            )
            .put(
                "name",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 48)
                    .put("description", "Subagent profile name in kebab-case (e.g. \"bill-analyzer\", \"log-auditor\"). Required for get, save, and delete."),
            )
            .put(
                "description",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 240)
                    .put("description", "Required for save: concise one-line summary of what this custom subagent does."),
            )
            .put(
                "when_to_use",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 500)
                    .put("description", "Optional for save: guidance on when the main agent should delegate to this subagent."),
            )
            .put(
                "tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 64)
                    .put("description", "Optional for save: list of allowed tool names for this subagent (e.g. [\"summarize_bills\", \"search_bills\", \"search_personal_orders\"]). Omit or pass [\"*\"] to allow all non-GUI tools."),
            )
            .put(
                "disallowed_tools",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("maxItems", 64)
                    .put("description", "Optional for save: list of tool names to explicitly disallow."),
            )
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 16_000)
                    .put("description", "Required for save: system instructions / role prompt for the custom subagent."),
            )
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("action")))
            .put("additionalProperties", false)
        return AgentToolSchema.function(
            name = PROFILE_TOOL_NAME,
            description = "Manage persistent reusable Subagent templates (`agents/<name>.md`). ONLY call `save` or `delete` when the user explicitly asks to save a reusable custom Subagent template for future sessions. For spawning specialized subagents to do work during a conversation, call `subagent` directly with an ad-hoc `subagent_type` and `role` without saving a profile.",
            parameters = parameters,
        )
    }
}
