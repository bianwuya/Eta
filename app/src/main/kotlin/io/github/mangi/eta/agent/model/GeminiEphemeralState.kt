package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 签名与搜索步骤只属于当前 run；稳定会话 codec 不读取这些字段。 */
internal object GeminiEphemeralState {
    private const val STEPS = "_eta_gemini_steps"
    private const val RUN_START = "_eta_gemini_run_start"
    private const val PENDING_SUGGESTIONS = "_eta_gemini_pending_suggestions"

    fun steps(message: JSONObject): JSONArray? = message.optJSONArray(STEPS)

    fun attachSteps(message: JSONObject, steps: JSONArray) {
        message.put(STEPS, JSONArray(steps.toString()))
    }

    fun copyAssistantState(source: JSONObject, target: JSONObject) {
        steps(source)?.let { attachSteps(target, it) }
        attachPendingSuggestions(target, pendingSuggestions(source))
    }

    fun pendingSuggestions(message: JSONObject): JSONArray =
        message.optJSONArray(PENDING_SUGGESTIONS) ?: JSONArray()

    fun attachPendingSuggestions(message: JSONObject, suggestions: JSONArray) {
        if (suggestions.length() > 0) message.put(PENDING_SUGGESTIONS, JSONArray(suggestions.toString()))
    }

    fun markRunStart(message: JSONObject) {
        message.put(RUN_START, true)
    }

    fun isRunStart(message: JSONObject): Boolean = message.optBoolean(RUN_START)

    fun hasPendingToolResponse(messages: JSONArray): Boolean {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant") {
                if (AssistantScreenContextProjection.isRuntimeReport(message)) continue
                val steps = steps(message) ?: return false
                return (0 until steps.length()).any {
                    steps.optJSONObject(it)?.optString("type") == "function_call"
                }
            }
        }
        return false
    }

    fun withoutState(message: JSONObject): JSONObject =
        if (!message.has(STEPS) && !message.has(RUN_START) && !message.has(PENDING_SUGGESTIONS)) message else JSONObject(message.toString()).apply {
            remove(STEPS)
            remove(RUN_START)
            remove(PENDING_SUGGESTIONS)
        }
}
