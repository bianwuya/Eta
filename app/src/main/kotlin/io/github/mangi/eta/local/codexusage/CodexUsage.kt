package io.github.mangi.eta.local.codexusage

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AuthorizedProviderHttp
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class UsageWindow(val name: String, val remaining: Double?, val seconds: Long?, val resetAt: Long?)
internal data class CodexUsage(val windows: List<UsageWindow>, val updatedAt: Long)

/** Passive official usage reader. Never consumes reset credits, selects an account or calls a model. */
internal object CodexUsageRepository {
    fun parse(body: JSONObject, timestamp: Long): CodexUsage {
        val windows = mutableListOf<UsageWindow>()
        fun append(name: String, limits: JSONObject?) {
            for (key in listOf("primary_window", "secondary_window")) {
                val window = limits?.optJSONObject(key) ?: continue
                val used = if (window.has("used_percent") && !window.isNull("used_percent")) window.optDouble("used_percent") else Double.NaN
                windows += UsageWindow(name.take(120), if (used.isFinite() && used in 0.0..100.0) 100.0 - used else null,
                    window.optLong("limit_window_seconds").takeIf { it > 0 },
                    window.optLong("reset_at").takeIf { it > 0 })
            }
        }
        append("codex", body.optJSONObject("rate_limit"))
        val extra = body.optJSONArray("additional_rate_limits")
        for (i in 0 until minOf(extra?.length() ?: 0, 24)) {
            val entry = extra?.optJSONObject(i) ?: continue
            append(entry.optString("limit_name").ifBlank { entry.optString("metered_feature", "附加额度") }, entry.optJSONObject("rate_limit"))
        }
        return CodexUsage(windows, timestamp)
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("codex_usage_cache_v1", Context.MODE_PRIVATE)
    private fun key(provider: String, account: String): String = android.net.Uri.encode(provider) + ":" + android.net.Uri.encode(account)
    fun cached(context: Context, provider: String, account: String): CodexUsage? = runCatching {
        prefs(context).getString(key(provider, account), null)?.let { raw ->
            val json = JSONObject(raw)
            val list = json.getJSONArray("windows")
            CodexUsage((0 until list.length()).map { i -> list.getJSONObject(i).let { item ->
                UsageWindow(item.getString("name"), if (item.isNull("remaining")) null else item.getDouble("remaining"),
                    item.optLong("seconds").takeIf { it > 0 }, item.optLong("reset_at").takeIf { it > 0 })
            } }, json.getLong("updated_at"))
        }
    }.getOrNull()

    fun refresh(context: Context, provider: String, account: String, controller: AgentRunController): CodexUsage {
        val binding = ProviderCredentialRepository.codexAccountBinding(provider, account)
        val request = Request.Builder().url(requireNotNull(ProviderConnectionProfile.OPENAI_CODEX.usageUrl)).get().build()
        val snapshot = AuthorizedProviderHttp.execute(AgentHttpClient.client, provider, ProviderAuthModes.OPENAI_CHATGPT,
            request, controller, binding) { response ->
            check(response.isSuccessful) { "额度读取失败（HTTP ${response.code}）" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 262144) { "额度响应过大" }
            val source = body.source()
            require(!source.request(262145)) { "额度响应过大" }
            parse(JSONObject(source.readUtf8()), System.currentTimeMillis())
        }
        controller.throwIfCancelled()
        val json = JSONObject().put("updated_at", snapshot.updatedAt).put("windows", JSONArray().also { rows ->
            snapshot.windows.forEach { item -> rows.put(JSONObject().put("name", item.name)
                .put("remaining", item.remaining ?: JSONObject.NULL).put("seconds", item.seconds ?: JSONObject.NULL)
                .put("reset_at", item.resetAt ?: JSONObject.NULL)) }
        })
        val preferences = prefs(context)
        val editor = preferences.edit()
        val cacheKey = key(provider, account)
        if (!preferences.contains(cacheKey) && preferences.all.size >= 32) {
            preferences.all.entries.minByOrNull { runCatching { JSONObject(it.value as String).optLong("updated_at") }.getOrDefault(0) }
                ?.let { editor.remove(it.key) }
        }
        check(editor.putString(cacheKey, json.toString()).commit()) { "额度已读取但缓存保存失败" }
        return snapshot
    }
}
