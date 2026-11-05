package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** Exact per-action reservation, not a semantic tool-name permission or a second scheduler. */
internal class PlanRunGate(private val conversationId: String, private val runId: String,
    private val controller: AgentRunController, private val planLineageId: String = runId) {
    private var stepId: String? = null
    private var stepRevision: Int? = null
    private var questionRound = -1
    private var proposalRound = -1
    private var questionUnanswered = false
    private var deliveredEditRevision = -1
    @Volatile private var active = true
    @Volatile private var acknowledgedSteeringVersion = controller.steeringVersion
    private val executedActions = mutableSetOf<String>()
    private data class ChildAction(val revision: Int, val stepId: String, val fingerprint: String)
    private val childActions = mutableMapOf<String, ChildAction>()

    fun childReport(taskId: String, succeeded: Boolean) {
        val action = childActions[taskId] ?: return
        PlanModeStore.completeChild(runId, action.revision, action.stepId, taskId, action.fingerprint, succeeded)
        childActions.remove(taskId)
    }

    companion object {
        const val PROPOSE = "plan_propose"
        const val BEGIN = "plan_begin_step"
        val READ_ONLY = setOf("read_file", "stat_file", "list_directory", "glob_files", "grep_files",
            "skills_list", "skills_read", "skills_read_resource", "web_search", "fetch_url", "get_current_context",
            "device_status", "network_info", "search_apps", "inspect_app", "search_notes", "read_note",
            "search_calendar_events", "read_calendar_event", "conversation_history", "search_files", "search_contacts", "read_personal_item")
        private val INTERNAL = setOf(PROPOSE, BEGIN, "ask_user_questions")

        fun schemas(tools: JSONArray) {
            fun str() = JSONObject().put("type", "string")
            val action = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("tool_name", str()).put("arguments", JSONObject().put("type", "object")))
                .put("required", JSONArray().put("tool_name").put("arguments"))
            val step = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", str()).put("title", str())
                .put("depends_on", JSONObject().put("type", "array").put("items", str()))
                .put("independent_of_questions", JSONObject().put("type", "boolean"))
                .put("actions", JSONObject().put("type", "array").put("items", action)))
                .put("required", JSONArray().put("id").put("title").put("actions"))
            tools.put(AgentToolSchema.function(PROPOSE, "提交完整计划及每个副作用工具调用的精确参数，等待用户在界面确认；不可通过工具批准。修改后重新提交。",
                JSONObject().put("type", "object").put("properties", JSONObject().put("title", str()).put("content", str())
                    .put("steps", JSONObject().put("type", "array").put("items", step)))
                    .put("required", JSONArray().put("title").put("content").put("steps"))))
            tools.put(AgentToolSchema.function(BEGIN, "选择已批准版本中前置步骤完成的步骤，然后原样调用其中依次列出的工具参数。",
                JSONObject().put("type", "object").put("properties", JSONObject().put("step_id", str()).put("revision", JSONObject().put("type", "integer")))
                    .put("required", JSONArray().put("step_id").put("revision"))))
        }
        val PROMPT = "计划模式：先只读分析、集中询问必要问题，再用 plan_propose 提交完整计划和逐步的精确工具参数。仅本人界面批准当前版本可执行；提问回答本身不是批准。批准后用 plan_begin_step 选择步骤，再按批准参数原样调用工具。修改、过期或未知结果不得重试，需重新规划。未答问题只阻塞依赖回答的步骤；已在本人批准的当前版本明确标记独立、前置完成且精确参数不变的步骤可以继续，不因超时重复审批。新增操作、版本或权限仍需本人批准；同批提问后先读取结果再执行副作用。子代理的执行同样受约束。"

        internal fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> throw IllegalArgumentException("无效参数类型")
        }
    }

    private fun result(ok: Boolean, code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", ok).put("code", code).put("message", message).toString())

    fun end() { active = false }
    fun mayAct(): Boolean = active && !controller.isCancelled && !controller.hasPendingSteering &&
        controller.steeringVersion == acknowledgedSteeringVersion

    fun onSteering(sequence: Long = controller.steeringVersion) {
        PlanModeStore.invalidateForSteering(runId)
        stepId = null; stepRevision = null
        acknowledgedSteeringVersion = sequence
    }

    fun editedPlanMessage(): String? {
        val p = PlanModeStore.current(runId) ?: return null
        if (p.status != "draft" || !p.editPending || p.revision == deliveredEditRevision) return null
        deliveredEditRevision = p.revision
        return "本人修改了计划，当前版本 ${p.revision} 尚未授权；按以下内容重新规划并提交精确步骤，不能沿用旧批准。" +
            (if (p.interruptedWork) "此前在途副作用可能已发生，请先核实。" else "") + "\n" + p.content
    }

    fun noteQuestion(round: Int, content: String) {
        questionRound = round
        if (!runCatching { JSONObject(content).optString("status") == "answered" }.getOrDefault(false)) {
            questionUnanswered = true
            PlanModeStore.markQuestionsBlocked(runId)
        }
    }

    fun execute(call: AgentModelClient.ToolCall, knownChildLaunch: (JSONObject) -> Boolean = { false },
        delegate: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!mayAct()) return result(false, "PLAN_STEERING_PENDING", "任务已结束或用户新引导待完整边界处理；旧调用未执行")
        val current = PlanModeStore.current(runId)
        if (current?.status == "draft" && current.editPending && current.revision != deliveredEditRevision)
            return result(false, "PLAN_EDIT_PENDING", "用户已修改计划；先在完整边界读取修改，旧调用未执行")
        if (call.name == PROPOSE) {
            if (call.round == questionRound) return result(false, "PLAN_TURN_BOUNDARY", "先读取提问结果再制定计划")
            proposalRound = call.round; return propose(call, current?.revision)
        }
        if (call.name == BEGIN) {
            if (call.round == proposalRound || call.round == questionRound)
                return result(false, "PLAN_TURN_BOUNDARY", "请先接收本批结果后再选择步骤")
            return begin(call)
        }
        if (call.name == "ask_user_questions") {
            if (call.round == questionRound) return result(false, "PLAN_QUESTION_BOUNDARY", "先读取本批已有的提问结果")
            return delegate()
        }
        if (call.name in READ_ONLY) return delegate()
        if (call.round == questionRound || call.round == proposalRound) return result(false, "PLAN_QUESTION_BOUNDARY", "本批有提问；先让模型读取结果，旧副作用调用未执行")
        val p = PlanModeStore.current(runId) ?: return result(false, "PLAN_APPROVAL_REQUIRED", "尚无获批准计划；调用未执行")
        val selected = p.steps.firstOrNull { it.id == stepId }
        if (p.status != "approved" || p.approvedRevision != p.revision || stepRevision != p.revision || selected == null)
            return result(false, "PLAN_APPROVAL_REQUIRED", "计划版本未批准或未选择步骤；调用未执行")
        if (selected.status != "pending" || selected.dependsOn.any { dep -> p.steps.none { it.id == dep && it.status == "completed" } } ||
            (questionUnanswered && !p.questionBlocked) ||
            (p.questionBlocked && (!p.independenceApproved || !selected.independentOfQuestions)))
            return result(false, "PLAN_STEP_BLOCKED", "步骤依赖未完成或问题未回答；调用未执行")
        val expected = selected.actions.getOrNull(selected.nextAction)
            ?: return result(false, "PLAN_STEP_COMPLETE", "该步骤没有更多已批准调用")
        val actualArgs = runCatching { canonical(JSONObject(call.argumentsJson)) }.getOrNull()
        if (expected.toolName != call.name || actualArgs == null || expected.arguments != actualArgs)
            return result(false, "PLAN_ACTION_MISMATCH", "工具名或参数与批准的当前步骤不一致；调用未执行")
        // A revised plan must not silently replay an earlier in-flight/unknown action.
        val fingerprint = expected.fingerprint()
        if (fingerprint in executedActions || fingerprint in p.uncertainActions)
            return result(false, "PLAN_ACTION_ALREADY_ATTEMPTED", "相同副作用已尝试或结果未知；不得自动重放，请先核实")
        // Persist a reservation first, rechecking revision, step, dependency and pending steering under Store lock.
        if (!PlanModeStore.reserveAction(runId, p.revision, selected, expected, fingerprint, ::mayAct))
            return result(false, "PLAN_REVISION_CHANGED", "计划、问题或用户引导已变化；调用未执行")
        if (!PlanModeStore.canDispatch(runId, p.revision, selected.id, selected.nextAction, fingerprint, ::mayAct)) {
            runCatching { PlanModeStore.abortReservation(runId, p.revision, selected.id, selected.nextAction, fingerprint) }
            return result(false, "PLAN_REVISION_CHANGED", "分发前计划或用户引导已变化；调用未执行")
        }
        executedActions += fingerprint
        val response = try { delegate() } catch (failure: Throwable) {
            runCatching { PlanModeStore.finishAction(runId, p.revision, selected.id, selected.nextAction, fingerprint, false) }
            throw failure
        }
        val outcome = runCatching { JSONObject(response.content) }.getOrNull()
        val terminalExitOk = if (call.name == "terminal" && outcome?.has("exit_code") == true) {
            val exit = outcome.opt("exit_code")
            exit is Number && exit.toDouble() == 0.0
        } else true
        // A persisted child launch is known dispatch, not completion of the child's work.
        // Keep its dependency waiting until the owner's typed final report arrives.
        val childTask = if (call.name == "subagent" && outcome != null && knownChildLaunch(outcome))
            outcome.optString("task_id").takeIf { it.isNotBlank() } else null
        val success = childTask != null || terminalExitOk && outcome?.optBoolean("ok", false) == true && outcome.optString("status").lowercase() !in setOf("accepted", "started", "queued", "running", "pending", "unknown", "timed_out", "timeout", "cancelled", "failed", "error", "interrupted") &&
            !outcome.optBoolean("timed_out", false) && call.name !in setOf("terminal_job", "terminal_daemon") &&
            outcome.optString("code") !in setOf("ACTION_OUTCOME_UNKNOWN", "DIRECTION_MISMATCH")
        val recorded = try { PlanModeStore.finishAction(runId, p.revision, selected.id, selected.nextAction, fingerprint, success, childTask) }
        catch (failure: Exception) {
            return response.copy(content = JSONObject().put("ok", false).put("code", "PLAN_RESULT_RECORD_FAILED")
                .put("message", "操作已发出但结果持久化失败；不得自动重放。以下为实际返回，请先核实。")
                .put("tool_result", outcome ?: response.content).toString())
        }
        if (recorded && childTask != null) childActions[childTask] = ChildAction(p.revision, selected.id, fingerprint)
        if (!recorded) return response.copy(content = JSONObject().put("ok", false).put("code", "PLAN_CHANGED_AFTER_IO")
            .put("message", "操作在途时计划已变化，不能撤销；以下为实际返回，先核实再规划，不得自动重放。")
            .put("tool_result", outcome ?: response.content).toString())
        if (!terminalExitOk) return response.copy(content = JSONObject().put("ok", false).put("code", "PLAN_TERMINAL_EXIT_NONZERO")
            .put("message", "终端返回非零或无效退出码，步骤未完成；以下为原始返回。")
            .put("tool_result", outcome ?: response.content).toString())
        return response
    }

    /** Children may inspect; even an approved launch is not a blanket delegation of writes. */
    fun executeChild(call: AgentModelClient.ToolCall, delegate: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!mayAct() || call.name !in READ_ONLY)
            return result(false, "PLAN_CHILD_BLOCKED", "子代理仅可调用只读集合；写入须由主代理按批准步骤执行")
        return delegate()
    }

    private fun propose(call: AgentModelClient.ToolCall, expectedRevision: Int?): AgentModelClient.ToolResult {
        val args = JSONObject(call.argumentsJson)
        val items = args.getJSONArray("steps")
        require(items.length() in 1..40)
        val steps = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val id = item.getString("id"); val title = item.getString("title")
            require(id.isNotBlank() && id.length <= 80 && title.isNotBlank() && title.length <= 200)
            val actionJson = item.getJSONArray("actions")
            require(actionJson.length() in 1..30)
            val actions = (0 until actionJson.length()).map { n ->
                val a = actionJson.getJSONObject(n)
                val name = a.getString("tool_name")
                require(name.isNotBlank() && name.length <= 160 && name !in INTERNAL && name !in READ_ONLY)
                PlanAction(name, canonical(a.getJSONObject("arguments")).also { require(it.length <= 20000) })
            }
            val deps = item.optJSONArray("depends_on") ?: JSONArray()
            val dependencies = (0 until deps.length()).map { deps.getString(it) }
            PlanStep(id, title, actions = actions, dependsOn = dependencies,
                independentOfQuestions = item.optBoolean("independent_of_questions", false))
        }
        // Restrict dependencies to earlier steps: no cycles or speculative readiness.
        steps.forEachIndexed { i, s -> require(s.dependsOn.all { dep -> steps.take(i).any { it.id == dep } }) }
        val p = PlanModeStore.propose(conversationId, runId, args.getString("title"), args.getString("content"), steps, questionBlocked = questionUnanswered, expectedRevision = expectedRevision,
            planLineageId = planLineageId, mayPublish = ::mayAct)
        stepId = null; stepRevision = null
        controller.awaitWorkAvailableFor(PlanModeStore.approvalRemainingMs(runId, p.revision)) {
            !mayAct() || PlanModeStore.current(runId)?.let { it.revision != p.revision || it.status != "awaiting_approval" } != false
        }
        PlanModeStore.expireApproval(runId, p.revision)
        val current = PlanModeStore.current(runId)
        return when {
            current == null -> result(false, "PLAN_ENDED", "运行已结束；未授权")
            current.revision != p.revision -> result(false, "PLAN_CHANGED", "用户修改了计划；需重新提交精确步骤。" +
                (if (current.interruptedWork) "此前在途副作用可能已发生，须先核实。" else "") + "\n" + current.content)
            current.approvedRevision == p.revision -> result(true, "PLAN_APPROVED", "用户已批准此版本；选择步骤后按精确参数执行")
            else -> result(false, "PLAN_UNANSWERED", "未批准或等待超时；不得执行副作用")
        }
    }

    private fun begin(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val a = JSONObject(call.argumentsJson)
        val p = PlanModeStore.current(runId) ?: return result(false, "PLAN_APPROVAL_REQUIRED", "无活动计划")
        val revision = a.getInt("revision")
        val s = p.steps.firstOrNull { it.id == a.getString("step_id") }
        if (revision != p.revision || p.approvedRevision != revision || p.status != "approved" || s == null ||
            s.status != "pending" || s.dependsOn.any { dep -> p.steps.none { it.id == dep && it.status == "completed" } } ||
            (questionUnanswered && !p.questionBlocked) ||
            (p.questionBlocked && (!p.independenceApproved || !s.independentOfQuestions)))
            return result(false, "PLAN_STEP_BLOCKED", "版本未批准、步骤终止或依赖未满足")
        stepId = s.id; stepRevision = revision
        return result(true, "PLAN_STEP_READY", "请按本步骤的精确参数顺序调用工具")
    }
}
