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
import kotlinx.coroutines.CancellationException

internal data class CodexResetCredit(val id: String, val grantedAt: String, val expiresAt: String)

internal data class UsageWindow(val name: String, val remaining: Double?, val seconds: Long?, val resetAt: Long?)
internal data class CodexUsage(
    val windows: List<UsageWindow>,
    val updatedAt: Long,
    val resetCreditsAvailable: Int? = null,
    val resetCreditsApplicable: Int? = null,
    val resetCredits: List<CodexResetCredit> = emptyList(),
    val resetCreditsError: String? = null,
)

/** Passive official usage reader. Never consumes reset credits, selects an account or calls a model. */
internal object CodexUsageRepository {
    internal var httpClientProvider: () -> okhttp3.OkHttpClient = { AgentHttpClient.client }

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
        val resetCredits = body.optJSONObject("rate_limit_reset_credits")
        return CodexUsage(windows, timestamp, count(resetCredits, "available_count", "availableCount"),
            count(resetCredits, "applicable_available_count", "applicableAvailableCount"))
    }

    private fun count(body: JSONObject?, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        val value = body?.opt(key)
        val number = when (value) { is Number -> value.toDouble(); is String -> value.toDoubleOrNull(); else -> null }
        number?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble() }?.toInt()
    }

    internal fun withResetCredits(usage: CodexUsage, body: JSONObject): CodexUsage {
        require(listOf("credits", "available_count", "availableCount", "applicable_available_count", "applicableAvailableCount").any(body::has)) {
            "重置机会响应格式无效"
        }
        val credits = body.optJSONArray("credits")
        val details = (0 until minOf(credits?.length() ?: 0, 128)).mapNotNull { index ->
            val credit = credits?.optJSONObject(index) ?: return@mapNotNull null
            if (credit.optString("reset_type", credit.optString("resetType")) != "codex_rate_limits" ||
                credit.optString("status") != "available") return@mapNotNull null
            val expiry = credit.optString("expires_at", credit.optString("expiresAt"))
            if (expiry.isBlank()) return@mapNotNull null
            CodexResetCredit(credit.optString("id"), credit.optString("granted_at", credit.optString("grantedAt")), expiry)
        }
        return usage.copy(
            resetCreditsAvailable = count(body, "available_count", "availableCount") ?: usage.resetCreditsAvailable,
            resetCreditsApplicable = count(body, "applicable_available_count", "applicableAvailableCount") ?: usage.resetCreditsApplicable,
            resetCredits = details,
            resetCreditsError = null,
        )
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("codex_usage_cache_v1", Context.MODE_PRIVATE)
    internal fun key(provider: String, account: String): String = android.net.Uri.encode(provider) + ":" + android.net.Uri.encode(account)
    fun cached(context: Context, provider: String, account: String): CodexUsage? = runCatching {
        prefs(context).getString(key(provider, account), null)?.let { raw ->
            val json = JSONObject(raw)
            val list = json.getJSONArray("windows")
            val available = if (json.has("reset_credits_available") && !json.isNull("reset_credits_available")) {
                json.getInt("reset_credits_available").takeIf { it >= 0 }
            } else null
            CodexUsage((0 until list.length()).map { i -> list.getJSONObject(i).let { item ->
                UsageWindow(item.getString("name"), if (item.isNull("remaining")) null else item.getDouble("remaining"),
                    item.optLong("seconds").takeIf { it > 0 }, item.optLong("reset_at").takeIf { it > 0 })
            } }, json.getLong("updated_at"), available,
                resetCreditsApplicable = count(json, "reset_credits_applicable"),
                resetCredits = json.optJSONArray("reset_credit_details")?.let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                        CodexResetCredit(row.getString("id"), row.optString("granted_at"), row.getString("expires_at"))
                    } }
                }.orEmpty(),
                resetCreditsError = json.optString("reset_credits_error").takeIf(String::isNotBlank))
        }
    }.getOrNull()

    fun refresh(context: Context, provider: String, account: String, controller: AgentRunController): CodexUsage {
        val binding = ProviderCredentialRepository.codexAccountBinding(provider, account)
        val request = Request.Builder().url(requireNotNull(ProviderConnectionProfile.OPENAI_CODEX.usageUrl)).get().build()
        var snapshot = AuthorizedProviderHttp.execute(httpClientProvider(), provider, ProviderAuthModes.OPENAI_CHATGPT,
            request, controller, binding) { response ->
            check(response.isSuccessful) { "额度读取失败（HTTP ${response.code}）" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 262144) { "额度响应过大" }
            val source = body.source()
            require(!source.request(262145)) { "额度响应过大" }
            parse(JSONObject(source.readUtf8()), System.currentTimeMillis())
        }
        controller.throwIfCancelled()
        // CPA uses a separate read endpoint for credit details. Both requests belong to
        // the binding captured above, even when the account selection changes meanwhile.
        snapshot = try {
            val creditsRequest = Request.Builder()
                .url(requireNotNull(ProviderConnectionProfile.OPENAI_CODEX.rateLimitResetCreditsUrl)).get().build()
            val client = httpClientProvider().newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            AuthorizedProviderHttp.execute(client, provider, ProviderAuthModes.OPENAI_CHATGPT, creditsRequest,
                controller, binding) { response ->
                check(response.isSuccessful) { "重置机会读取失败（HTTP ${response.code}）" }
                val body = response.body
                require(body.contentLength() <= 262144) { "重置机会响应过大" }
                val source = body.source()
                require(!source.request(262145)) { "重置机会响应过大" }
                withResetCredits(snapshot, JSONObject(source.readUtf8()))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            controller.throwIfCancelled()
            snapshot.copy(resetCreditsError = "重置机会明细读取失败，已保留额度读数")
        }
        controller.throwIfCancelled()
        val json = JSONObject().put("updated_at", snapshot.updatedAt).put("windows", JSONArray().also { rows ->
            snapshot.windows.forEach { item -> rows.put(JSONObject().put("name", item.name)
                .put("remaining", item.remaining ?: JSONObject.NULL).put("seconds", item.seconds ?: JSONObject.NULL)
                .put("reset_at", item.resetAt ?: JSONObject.NULL)) }
        })
        if (snapshot.resetCreditsAvailable != null) {
            json.put("reset_credits_available", snapshot.resetCreditsAvailable)
        }
        snapshot.resetCreditsApplicable?.let { json.put("reset_credits_applicable", it) }
        snapshot.resetCreditsError?.let { json.put("reset_credits_error", it) }
        json.put("reset_credit_details", JSONArray().also { rows ->
            snapshot.resetCredits.forEach { credit -> rows.put(JSONObject().put("id", credit.id)
                .put("granted_at", credit.grantedAt).put("expires_at", credit.expiresAt)) }
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
