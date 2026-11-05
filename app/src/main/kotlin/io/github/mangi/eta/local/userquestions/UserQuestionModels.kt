package io.github.mangi.eta.local.userquestions

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentToolSchema

@Serializable
internal data class QuestionOption(val label: String, val description: String = "")
@Serializable
internal data class UserQuestion(val prompt: String, val tab: String, val multiple: Boolean,
    val allowOther: Boolean, val options: List<QuestionOption>)
@Serializable
internal data class QuestionDraft(val selected: Set<Int> = emptySet(), val otherSelected: Boolean = false, val other: String = "")
@Serializable
internal enum class UserQuestionPresentation { COLLAPSED, EXPANDED, HIDDEN }
@Serializable
internal data class UserQuestionSet(val id: String, val conversationId: String, val runId: String,
    val callId: String, val intro: String, val questions: List<UserQuestion>, val drafts: List<QuestionDraft>,
    val status: String = "waiting", val active: Boolean = true, val summary: String = "",
    val presentation: UserQuestionPresentation = UserQuestionPresentation.COLLAPSED,
    val hostId: String = "main", val planId: String? = null, val planRevision: Int? = null,
    val endReason: String = "")

internal object UserQuestionParser {
    fun parse(args: JSONObject): Pair<String, List<UserQuestion>> {
        val intro = args.optString("intro")
        require(intro.length <= 200) { "提问说明过长" }
        val list = args.getJSONArray("questions")
        require(list.length() in 1..4) { "一次提问需1到4题" }
        val questions = (0 until list.length()).map { i ->
            val q = list.getJSONObject(i)
            val prompt = q.getString("prompt").trim()
            val tab = q.optString("tab", "问题${i + 1}").trim()
            val options = q.optJSONArray("options") ?: JSONArray()
            require(prompt.isNotBlank() && prompt.length <= 400 && tab.isNotBlank() && tab.length <= 12 && options.length() <= 6) { "问题或选项超过限制" }
            val allowOther = q.optBoolean("allow_other")
            UserQuestion(prompt, tab, q.optBoolean("multiple"), allowOther, (0 until options.length()).map { n ->
                val item = options.getJSONObject(n)
                QuestionOption(item.getString("label"), item.optString("description")).also {
                    require(it.label.isNotBlank() && it.label.length <= 60 && it.description.length <= 160) { "选项文字超过限制" }
                }
            })
        }
        return intro to questions
    }
    // otherSelected and allowOther are retained for old data, not gates for free text.
    fun validDraft(question: UserQuestion, draft: QuestionDraft): Boolean =
        draft.selected.all { it in question.options.indices } &&
            (question.multiple || draft.selected.size <= 1) && draft.other.length <= 2000

    fun valid(question: UserQuestion, draft: QuestionDraft): Boolean =
        validDraft(question, draft) && (draft.selected.isNotEmpty() || draft.other.isNotBlank())
}

internal object AgentUserQuestionsToolCatalog {
    val NAMES = setOf("ask_user_questions")
    fun appendTo(tools: JSONArray) {
        val option = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("label", JSONObject().put("type", "string").put("maxLength", 60))
            .put("description", JSONObject().put("type", "string").put("maxLength", 160)))
            .put("required", JSONArray().put("label"))
        val question = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("prompt", JSONObject().put("type", "string").put("maxLength", 400))
            .put("tab", JSONObject().put("type", "string").put("maxLength", 12))
            .put("multiple", JSONObject().put("type", "boolean"))
            .put("allow_other", JSONObject().put("type", "boolean")
                .put("description", "历史兼容字段；每题始终支持自由填写，可与选项同时提交。"))
            .put("options", JSONObject().put("type", "array").put("maxItems", 6).put("items", option)))
            .put("required", JSONArray().put("prompt"))
        tools.put(AgentToolSchema.function("ask_user_questions", "缺少关键参数或需要用户决策时，在原会话显示紧凑选项卡，一次1到4题，统一提交后继续原任务。" +
            "每题始终可自由填写，支持选项加文字或纯文字。此工具等待本人回答；未回答、稍后、取消或任务已结束绝不代表同意，不得据此执行待批准操作。不要用于每条日常回复。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("intro", JSONObject().put("type", "string").put("maxLength", 200))
                .put("questions", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 4).put("items", question)))
                .put("required", JSONArray().put("questions"))))
    }
}
