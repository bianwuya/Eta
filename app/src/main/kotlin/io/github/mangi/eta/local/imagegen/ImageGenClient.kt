package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.OkHttpClient
import org.json.JSONObject

internal class ImageGenClient(private val context: Context, client: OkHttpClient = AgentHttpClient.client,
    private val credentials: ImageGenCredentials = ImageGenCredentials(context),
) {
    private val http = client.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS).callTimeout(360, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun generate(prompt: String, controller: AgentRunController, config: ImageGenConfig): JSONObject {
        config.validate()?.let { return failure("IMAGE_GEN_NOT_CONFIGURED", it) }
        if (prompt.isBlank() || prompt.length > 16000) return failure("IMAGE_GEN_INVALID_PROMPT", "图片描述须为1到16000字符")
        controller.throwIfCancelled()
        val subscription = config.source == ImageGenSource.CODEX_SUBSCRIPTION
        val profile = ProviderConnectionProfile.OPENAI_CODEX
        val url = if (subscription) requireNotNull(profile.imageGenerationUrl) else config.endpoint
        val binding = try {
            if (subscription) ProviderCredentialRepository.codexAccountBinding(config.credentialPoolId, config.accountId) else null
        } catch (_: ProviderAuthException) {
            return failure("IMAGE_GEN_ACCOUNT_MISSING", "固定 Codex 账号已删除、未登录或需要重新授权")
        }
        val body = requestBody(prompt, config)
        val headers = okhttp3.Headers.Builder()
        ProviderRequestHeaders.mergeInto(headers, if (subscription) profile.baseUrl else config.endpoint,
            emptyList(), authMode = if (subscription) ProviderAuthModes.OPENAI_CHATGPT else ProviderAuthModes.API_KEY,
            model = config.model)
        val token = try {
            if (binding != null) ProviderCredentialRepository.accessToken(binding, controller, null)
            else credentials.read(config.keySlotId).ifBlank {
                return failure("IMAGE_GEN_API_KEY_MISSING", "独立生图 API Key 不可用，请重新填写")
            }
        } catch (_: ProviderAuthException) {
            return failure("IMAGE_GEN_AUTH_EXPIRED", "固定生图凭据不可用，请重新登录或填写 Key")
        }
        headers.set("Authorization", "Bearer $token")
        if (subscription) {
            headers.set("x-codex-image-turn-id", UUID.randomUUID().toString())
            headers.set("ChatGPT-Account-Id", io.github.mangi.eta.agent.auth.CodexOAuth.accountId(token))
        }
        val request = Request.Builder().url(url).headers(headers.build())
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            if (subscription) profile.requireRequestTarget(request.url)
            // A paid request is executed once. Even a 401 does not replay the generation POST.
            val call = http.newCall(request)
            val cancellation = controller.register(call::cancel)
            try {
                controller.throwIfCancelled()
                call.execute().use { response -> consume(response, config, controller) }
            } finally { cancellation.close() }
        } catch (_: ProviderAuthException) {
            controller.throwIfCancelled()
            failure("IMAGE_GEN_AUTH_EXPIRED", "固定生图账号授权失效，请重新登录；未切换来源")
        } catch (_: IOException) {
            controller.throwIfCancelled()
            failure("IMAGE_GEN_OUTCOME_UNKNOWN", "生图请求连接中断或超时，服务端可能已生成并扣额。未自动重试，请核对后决定是否再次生成。", unknown = true)
        } catch (_: Exception) {
            controller.throwIfCancelled()
            failure("IMAGE_GEN_RESULT_FAILED", "生图返回或图片保存失败，服务端可能已扣额。未自动重试，请核对输出目录后再决定。", unknown = true)
        }
    }

    private fun consume(response: Response, config: ImageGenConfig, controller: AgentRunController): JSONObject {
        val requestId = response.header("x-codex-imagegen-request-id") ?: response.header("x-request-id")
        if (!response.isSuccessful) {
            val error = runCatching { JSONObject(response.peekBody(65536).string()).optJSONObject("error") }.getOrNull()
            val code = error?.optString("code").orEmpty()
            val type = error?.optString("type").orEmpty()
            val quota = code in setOf("usage_limit_reached", "insufficient_quota", "quota_exceeded") ||
                type == "usage_limit_reached"
            return when {
                response.code == 401 -> failure("IMAGE_GEN_AUTH_EXPIRED", "固定生图账号授权失效，请重新登录")
                quota -> failure("IMAGE_GEN_QUOTA_EXCEEDED", "生图额度不足，请查看服务端额度窗口和重置时间").also {
                    error?.optLong("resets_at")?.takeIf { it > 0 }?.let { reset -> it.put("quota_reset_at", reset) }
                }
                response.code >= 500 || response.code == 408 -> failure("IMAGE_GEN_OUTCOME_UNKNOWN",
                    "生图服务返回HTTP ${response.code}，执行结果未确认；未自动重试以免重复扣额。", unknown = true)
                else -> failure("IMAGE_GEN_REJECTED", "生图接口拒绝本次请求（HTTP ${response.code}），请检查所选模型、参数及账号权限")
            }.also { if (requestId != null) it.put("request_id", requestId) }
        }
        val responseBody = response.body ?: return failure("IMAGE_GEN_EMPTY", "生图接口未返回图片，未自动重试", unknown = true)
        val maxResponse = ImageGenOutput.MAX_BASE64.toLong() + 1024 * 1024
        require(responseBody.contentLength() <= maxResponse) { "图片响应过大" }
        val source = responseBody.source()
        require(!source.request(maxResponse + 1)) { "图片响应过大" }
        controller.throwIfCancelled()
        val data = JSONObject(source.readUtf8()).optJSONArray("data")
        val encoded = data?.optJSONObject(0)?.optString("b64_json").orEmpty()
        if (data?.length() != 1 || encoded.isBlank()) return failure("IMAGE_GEN_EMPTY",
            "接口未返回一张Base64图片；本入口支持兼容Images生成协议，不会将文本或URL冒充图片。", unknown = true)
        val saved = ImageGenOutput.save(encoded, controller)
        return JSONObject().put("ok", true).put("path", saved.file.absolutePath).put("mime", saved.mime)
            .put("width", saved.width).put("height", saved.height).put("model", config.model).put("source", config.source)
            .put("markdown", "![生成图片](<${saved.file.absolutePath}>)")
            .put("note", "真实图片已保存。请用markdown引用展示；不要用程序绘图替代。")
            .also { if (requestId != null) it.put("request_id", requestId) }
    }

    companion object {
        internal fun requestBody(prompt: String, config: ImageGenConfig): JSONObject = JSONObject()
            .put("prompt", prompt).put("model", config.model).put("size", config.size)
            .put("quality", config.quality).put("background", config.background).put("n", 1)
            .also { if (config.source == ImageGenSource.OPENAI_API && !config.model.startsWith("gpt-image")) it.put("response_format", "b64_json") }
        internal fun failure(code: String, message: String, unknown: Boolean = false) = JSONObject()
            .put("ok", false).put("code", code).put("message", message).put("retryable", false)
            .put("outcome_unknown", unknown).put("automatic_retry", false)
    }
}
