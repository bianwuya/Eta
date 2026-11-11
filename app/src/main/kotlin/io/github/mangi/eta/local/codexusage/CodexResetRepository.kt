package io.github.mangi.eta.local.codexusage

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AuthorizedProviderHttp
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class ResetOutcomeCode {
    RESET,
    ALREADY_REDEEMED,
    NOTHING_TO_RESET,
    NO_CREDIT,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): ResetOutcomeCode = when (value) {
            "reset" -> RESET
            "already_redeemed" -> ALREADY_REDEEMED
            "nothing_to_reset" -> NOTHING_TO_RESET
            "no_credit" -> NO_CREDIT
            else -> UNKNOWN
        }
    }
}

internal sealed interface CodexResetResult {
    data class Success(
        val code: ResetOutcomeCode,
        val windowsReset: Int,
        val usage: CodexUsage,
        val resetWindowNames: List<String> = emptyList(),
    ) : CodexResetResult

    data class SuccessPendingRefresh(
        val code: ResetOutcomeCode,
        val windowsReset: Int,
        val refreshError: Throwable,
        val resetWindowNames: List<String> = emptyList(),
    ) : CodexResetResult

    data class Rejected(
        val code: ResetOutcomeCode,
        val windowsReset: Int = 0,
        val resetWindowNames: List<String> = emptyList(),
    ) : CodexResetResult

    data class Failed(
        val error: Throwable,
        val unconfirmed: Boolean,
    ) : CodexResetResult
}

internal object CodexResetRepository {
    private const val PREFS_NAME = "codex_reset_pending_v1"
    private val _inFlightKeys = MutableStateFlow<Set<String>>(emptySet())
    val inFlightKeys: StateFlow<Set<String>> = _inFlightKeys.asStateFlow()

    private val inFlightDeferreds = ConcurrentHashMap<String, CompletableDeferred<CodexResetResult>>()

    internal var httpClientProvider: () -> OkHttpClient = { AgentHttpClient.client }
    internal var usageRefresher: (Context, String, String, AgentRunController) -> CodexUsage =
        { ctx, p, a, c -> CodexUsageRepository.refresh(ctx, p, a, c) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun key(providerId: String, accountId: String): String =
        CodexUsageRepository.key(providerId, accountId)

    fun isInFlight(providerId: String, accountId: String): Boolean =
        _inFlightKeys.value.contains(key(providerId, accountId))

    fun pendingRedeemId(context: Context, providerId: String, accountId: String): String? {
        val raw = prefs(context).getString(key(providerId, accountId), null) ?: return null
        val id = JSONObject(raw).getString("redeem_request_id")
        require(UUID.fromString(id).toString() == id) { "上次重置记录无效" }
        return id
    }

    fun hasPendingConfirmation(context: Context, providerId: String, accountId: String): Boolean =
        pendingRedeemId(context, providerId, accountId) != null

    private fun recordPending(context: Context, providerId: String, accountId: String, redeemRequestId: String) {
        val json = JSONObject()
            .put("redeem_request_id", redeemRequestId)
            .put("created_at", System.currentTimeMillis())
        check(prefs(context).edit().putString(key(providerId, accountId), json.toString()).commit()) { "无法保存本次重置记录" }
        check(pendingRedeemId(context, providerId, accountId) == redeemRequestId) { "重置记录回读失败" }
    }

    internal fun clearPending(context: Context, providerId: String, accountId: String) {
        check(prefs(context).edit().remove(key(providerId, accountId)).commit()) { "无法更新重置记录" }
    }

    suspend fun consume(
        context: Context,
        providerId: String,
        accountId: String,
        controller: AgentRunController = AgentRunController(),
    ): CodexResetResult = withContext(Dispatchers.IO) {
        val opKey = key(providerId, accountId)
        val deferred = CompletableDeferred<CodexResetResult>()
        val existing = inFlightDeferreds.putIfAbsent(opKey, deferred)
        if (existing != null) {
            return@withContext existing.await()
        }
        _inFlightKeys.update { it + opKey }
        try {
            val result = executeConsume(context, providerId, accountId, controller)
            deferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            val failed = CodexResetResult.Failed(cancelled, unconfirmed = true)
            deferred.complete(failed)
            throw cancelled
        } catch (cancelled: AgentRunCancelledException) {
            val failed = CodexResetResult.Failed(cancelled, unconfirmed = true)
            deferred.complete(failed)
            throw cancelled
        } catch (t: Exception) {
            val failed = CodexResetResult.Failed(t, unconfirmed = prefs(context).contains(opKey))
            deferred.complete(failed)
            failed
        } catch (fatal: Throwable) {
            deferred.completeExceptionally(fatal)
            throw fatal
        } finally {
            inFlightDeferreds.remove(opKey)
            _inFlightKeys.update { it - opKey }
        }
    }

    fun consumeBlocking(
        context: Context,
        providerId: String,
        accountId: String,
        controller: AgentRunController = AgentRunController(),
    ): CodexResetResult = runBlocking(Dispatchers.IO) {
        consume(context, providerId, accountId, controller)
    }

    private fun executeConsume(
        context: Context,
        providerId: String,
        accountId: String,
        controller: AgentRunController,
    ): CodexResetResult {
        controller.throwIfCancelled()
        val binding = ProviderCredentialRepository.codexAccountBinding(providerId, accountId)
        val targetUrl = requireNotNull(ProviderConnectionProfile.OPENAI_CODEX.rateLimitResetConsumeUrl) {
            "未配置重置接口地址"
        }

        var redeemRequestId = pendingRedeemId(context, providerId, accountId)
        if (redeemRequestId == null) {
            redeemRequestId = UUID.randomUUID().toString()
            recordPending(context, providerId, accountId, redeemRequestId)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val payload = JSONObject().put("redeem_request_id", redeemRequestId).toString()
        val request = Request.Builder()
            .url(targetUrl)
            .post(payload.toRequestBody(mediaType))
            .build()

        data class RawResponse(val code: Int, val body: String)
        val rawResponse: RawResponse = try {
            AuthorizedProviderHttp.execute(
                client = httpClientProvider(),
                providerId = providerId,
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
                request = request,
                controller = controller,
                binding = binding,
            ) { response ->
                controller.throwIfCancelled()
                val code = response.code
                val responseBody = response.body
                require(responseBody.contentLength() <= 262144) { "重置响应过大" }
                val source = responseBody.source()
                require(!source.request(262145)) { "重置响应过大" }
                val body = source.readUtf8()
                RawResponse(code, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            return CodexResetResult.Failed(error = error, unconfirmed = true)
        }

        controller.throwIfCancelled()

        if (rawResponse.code !in 200..299) {
            return CodexResetResult.Failed(
                error = IOException("重置请求失败（HTTP " + rawResponse.code + "）"),
                unconfirmed = true,
            )
        }

        val json = try {
            JSONObject(rawResponse.body)
        } catch (e: Exception) {
            return CodexResetResult.Failed(
                error = IOException("重置响应解析失败"),
                unconfirmed = true,
            )
        }

        val rawCode = json.optString("code")
        val outcomeCode = ResetOutcomeCode.fromWire(rawCode)
        val (windowsCount, windowNames) = parseWindowsReset(json.opt("windows_reset"))

        return when (outcomeCode) {
            ResetOutcomeCode.RESET, ResetOutcomeCode.ALREADY_REDEEMED -> {
                clearPending(context, providerId, accountId)
                try {
                    controller.throwIfCancelled()
                    val refreshed = usageRefresher(context, providerId, accountId, controller)
                    CodexResetResult.Success(
                        code = outcomeCode,
                        windowsReset = windowsCount,
                        usage = refreshed,
                        resetWindowNames = windowNames,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: AgentRunCancelledException) {
                    throw cancelled
                } catch (refreshError: Exception) {
                    CodexResetResult.SuccessPendingRefresh(
                        code = outcomeCode,
                        windowsReset = windowsCount,
                        refreshError = refreshError,
                        resetWindowNames = windowNames,
                    )
                }
            }
            ResetOutcomeCode.NOTHING_TO_RESET, ResetOutcomeCode.NO_CREDIT -> {
                clearPending(context, providerId, accountId)
                CodexResetResult.Rejected(
                    code = outcomeCode,
                    windowsReset = windowsCount,
                    resetWindowNames = windowNames,
                )
            }
            ResetOutcomeCode.UNKNOWN -> {
                CodexResetResult.Failed(
                    error = IOException("未知的服务端重置结果"),
                    unconfirmed = true,
                )
            }
        }
    }

    private fun parseWindowsReset(raw: Any?): Pair<Int, List<String>> {
        if (raw == null || raw == JSONObject.NULL) return 0 to emptyList()
        require(raw is JSONArray) { "重置窗口响应无效" }
        val names = (0 until raw.length()).map { index ->
            requireNotNull(raw.get(index) as? String) { "重置窗口响应无效" }
        }
        return names.size to names
    }
}
