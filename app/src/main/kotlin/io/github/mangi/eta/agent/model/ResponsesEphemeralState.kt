package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 仅在单次 Agent run 内传递，稳定会话 codec 不认识并会主动丢弃。
 *
 * 原始 output items 含旧账号签发的 encrypted reasoning；同时记录产出它们的账号范围，
 * 跨账号时只降级回普通 assistant/tool_calls/tool outputs，绝不重放其他账号的状态。
 */
internal object ResponsesEphemeralState {
    private const val OUTPUT_ITEMS_KEY = "_eta_responses_output_items"
    private const val ACCOUNT_SCOPE_KEY = "_eta_responses_account_scope"

    fun outputItems(message: JSONObject): JSONArray? =
        message.optJSONArray(OUTPUT_ITEMS_KEY)

    /** 只返回与当前请求同一账号范围内产出的 items；范围不一致时返回 null 走普通历史降级。 */
    fun outputItems(message: JSONObject, accountScope: String?): JSONArray? {
        val items = outputItems(message) ?: return null
        val stored = message.optString(ACCOUNT_SCOPE_KEY).takeIf(String::isNotBlank)
        return items.takeIf { stored == accountScope }
    }

    fun attachOutputItems(message: JSONObject, items: JSONArray, accountScope: String? = null) {
        message.put(OUTPUT_ITEMS_KEY, JSONArray(items.toString()))
        if (!accountScope.isNullOrBlank()) message.put(ACCOUNT_SCOPE_KEY, accountScope)
    }

    fun copyOutputItems(source: JSONObject, target: JSONObject) {
        outputItems(source)?.let { items ->
            attachOutputItems(target, items, source.optString(ACCOUNT_SCOPE_KEY).takeIf(String::isNotBlank))
        }
    }
}
