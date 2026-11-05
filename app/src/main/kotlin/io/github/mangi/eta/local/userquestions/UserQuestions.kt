package io.github.mangi.eta.local.userquestions

import org.json.JSONArray
import org.json.JSONObject

/**
 * `ask_user_questions` 的类型化载荷。
 *
 * 模型输入是不可信外部数据：所有字段在进入 UI 与持久化之前按固定上限截断或拒绝；
 * 拒绝时返回明确的 INVALID_ARGUMENT，而不是静默猜测模型意图。
 */
internal data class UserQuestionOption(
    val label: String,
    val description: String = "",
)

internal data class UserQuestion(
    val id: String,
    val prompt: String,
    val tab: String,
    val multiple: Boolean,
    val allowOther: Boolean,
    val options: List<UserQuestionOption>,
)

internal data class UserQuestionSet(
    val setId: String,
    val conversationId: String,
    val runId: String,
    val intro: String,
    val questions: List<UserQuestion>,
)

internal data class UserQuestionAnswer(
    val questionId: String,
    val prompt: String,
    val selected: List<String> = emptyList(),
    val custom: Boolean = false,
    val other: String = "",
) {
    val isAnswered: Boolean get() = selected.isNotEmpty() || (custom && other.isNotBlank())
}

internal object UserQuestionParser {
    const val MAX_QUESTIONS = 4
    const val MAX_PROMPT = 400
    const val MAX_TAB = 12
    const val MAX_OPTIONS = 6
    const val MAX_LABEL = 60
    const val MAX_DESCRIPTION = 160
    const val MAX_INTRO = 200
    const val MAX_OTHER = 200
    const val MAX_SUMMARY = 600

    sealed interface Result {
        data class Ok(val set: UserQuestionSet) : Result
        data class Invalid(val message: String) : Result
    }

    fun parse(setId: String, conversationId: String, runId: String, args: JSONObject): Result {
        val raw = args.optJSONArray("questions")
            ?: return Result.Invalid("缺少 questions 数组")
        if (raw.length() < 1 || raw.length() > MAX_QUESTIONS) {
            return Result.Invalid("questions 需要 1 到 $MAX_QUESTIONS 个问题")
        }
        val intro = args.optString("intro").trim()
        if (intro.length > MAX_INTRO) return Result.Invalid("intro 不能超过 $MAX_INTRO 字")
        val questions = ArrayList<UserQuestion>(raw.length())
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index)
                ?: return Result.Invalid("questions[$index] 不是对象")
            val prompt = item.optString("prompt").trim()
            if (prompt.isBlank()) return Result.Invalid("questions[$index] 缺少 prompt")
            if (prompt.length > MAX_PROMPT) {
                return Result.Invalid("questions[$index] 题干超过 $MAX_PROMPT 字")
            }
            val tab = item.optString("tab").trim().ifBlank { "问题${index + 1}" }
            if (tab.length > MAX_TAB) return Result.Invalid("questions[$index] 选项卡超过 $MAX_TAB 字")
            val rawOptions = item.optJSONArray("options")
            if (rawOptions != null && rawOptions.length() > MAX_OPTIONS) {
                return Result.Invalid("questions[$index] 最多 $MAX_OPTIONS 个选项")
            }
            val options = ArrayList<UserQuestionOption>(rawOptions?.length() ?: 0)
            if (rawOptions != null) {
                for (optionIndex in 0 until rawOptions.length()) {
                    val option = rawOptions.optJSONObject(optionIndex)
                        ?: return Result.Invalid("questions[$index].options[$optionIndex] 不是对象")
                    val label = option.optString("label").trim()
                    if (label.isBlank()) {
                        return Result.Invalid("questions[$index].options[$optionIndex] 缺少 label")
                    }
                    if (label.length > MAX_LABEL) {
                        return Result.Invalid("questions[$index].options[$optionIndex] 选项文字超过 $MAX_LABEL 字")
                    }
                    val description = option.optString("description").trim()
                    if (description.length > MAX_DESCRIPTION) {
                        return Result.Invalid("questions[$index].options[$optionIndex] 说明超过 $MAX_DESCRIPTION 字")
                    }
                    if (options.any { it.label == label }) {
                        return Result.Invalid("questions[$index] 存在重复选项：$label")
                    }
                    options += UserQuestionOption(label, description)
                }
            }
            val allowOther = item.optBoolean("allow_other", false)
            if (options.isEmpty() && !allowOther) {
                return Result.Invalid("questions[$index] 没有 options 时必须设置 allow_other=true")
            }
            questions += UserQuestion(
                id = "q${index + 1}",
                prompt = prompt,
                tab = tab,
                multiple = item.optBoolean("multiple", false),
                allowOther = allowOther,
                options = options,
            )
        }
        return Result.Ok(UserQuestionSet(setId, conversationId, runId, intro, questions))
    }

    /** 统一提交前校验；返回 null 表示可以提交。 */
    fun validateAnswers(
        set: UserQuestionSet,
        answers: Map<String, UserQuestionAnswer>,
    ): String? {
        for (question in set.questions) {
            val answer = answers[question.id] ?: return "还有问题没有回答"
            if (answer.other.length > MAX_OTHER) return "补充回答不能超过 $MAX_OTHER 字"
            if (!answer.isAnswered) return "还有问题没有回答"
            if (answer.selected.any { label -> question.options.none { it.label == label } }) {
                return "答案包含不存在的选项"
            }
            if (!question.multiple && answer.selected.size > 1) return "单选题只能选一个选项"
            if (answer.selected.isNotEmpty() && answer.custom) return "请在选项和自行填写之间二选一"
            if (answer.custom && !question.allowOther) return "该问题不允许自行填写"
        }
        return null
    }

    fun summary(set: UserQuestionSet, answers: Map<String, UserQuestionAnswer>): String =
        set.questions.joinToString("；") { question ->
            val answer = answers[question.id]
            val value = buildList {
                answer?.selected?.forEach(::add)
                val other = answer?.other?.trim().orEmpty()
                if (answer?.custom == true && other.isNotEmpty()) add("其他：$other")
            }.joinToString("、")
            "${question.prompt.take(40)}：$value"
        }.take(MAX_SUMMARY)

    fun answeredResult(set: UserQuestionSet, answers: Map<String, UserQuestionAnswer>): String =
        JSONObject()
            .put("ok", true)
            .put("answered", true)
            .put("tool", "ask_user_questions")
            .put("summary", summary(set, answers))
            .put(
                "answers",
                JSONArray().also { array ->
                    set.questions.forEach { question ->
                        val answer = answers[question.id] ?: return@forEach
                        array.put(
                            JSONObject()
                                .put("question", question.prompt)
                                .put("selected", JSONArray(answer.selected))
                                .put("other", answer.other.trim()),
                        )
                    }
                },
            )
            .toString()

    fun unansweredResult(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("answered", false)
            .put("code", "USER_QUESTION_UNANSWERED")
            .put("message", message)
            .toString()

    fun invalidArguments(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("answered", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", message)
            .toString()
}
