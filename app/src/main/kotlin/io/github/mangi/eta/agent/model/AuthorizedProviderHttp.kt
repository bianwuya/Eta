package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 凭据在**请求开始时**解析一次并固定：[binding] 由调用方在发请求前取得，
 * 401 刷新与本次请求的请求头都只用这个绑定，不会因为 UI 切换账号而改用其他账号。
 * 401 恢复止于响应头，不重放已经开始的模型流。
 */
internal object AuthorizedProviderHttp {
    fun <T> execute(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        request: Request,
        controller: AgentRunController,
        binding: ProviderCredentialBinding? = null,
        accessToken: (ProviderCredentialBinding, AgentRunController, String?) -> String =
            { bound, run, rejected -> ProviderCredentialRepository.accessToken(bound, run, rejected) },
        consume: (Response) -> T,
    ): T {
        val profile = ProviderConnectionProfile.forAuthMode(authMode)
        profile?.requireRequestTarget(request.url)
        val httpClient = if (profile == null) client else client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        // 账号请求必须由调用方在请求开始时固定绑定；缺失时明确失败，不临时解析或空跑。
        val bound = if (profile == null) null else requireNotNull(binding) { "订阅请求缺少账号绑定" }
        var rejectedToken: String? = null
        var refreshed = false
        while (true) {
            controller.throwIfCancelled()
            val token = bound?.let { accessToken(it, controller, rejectedToken) }
            val authorized = if (token == null) request else request.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val trace = ModelDebugTrace.begin(authorized)
            val tracedClient = if (trace == null) httpClient else httpClient.newBuilder().eventListener(trace).build()
            val call = tracedClient.newCall(authorized)
            val callBinding = controller.register(call::cancel)
            try {
                controller.throwIfCancelled()
                call.execute().use { response ->
                    controller.throwIfCancelled()
                    if (profile != null && response.code == 401 && !refreshed) {
                        rejectedToken = token
                        refreshed = true
                    } else {
                        if (profile != null && response.code == 401) {
                            val failure = AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                            if (failure.code != "HTTP_401") throw failure
                            throw AgentModelFailure(
                                "OAUTH_UNAUTHORIZED", false, "订阅登录已失效，请重新登录服务商账户。",
                            )
                        }
                        return ModelDebugTrace.consume(trace) { consume(response) }
                    }
                }
            } catch (failure: Throwable) {
                trace?.failure("request_failed", failure)
                controller.throwIfCancelled()
                throw failure
            } finally {
                callBinding.close()
            }
        }
    }
}
