package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.UUID
import org.json.JSONObject

/** GPT 兼容请求的缓存路由提示；不是命中保证，不改变提示词或保存服务端对话。 */
internal object OpenAiPromptCache {
    fun apply(body: JSONObject, request: ProviderRequest) {
        val config = request.effectiveConfig
        if (config.authMode != ProviderAuthModes.API_KEY) return
        val model = config.model.lowercase()
        if (!model.startsWith("gpt-") && !model.startsWith("codex-")) return
        // 尊重显式配置，不把不同用途或不同服务商的会话归入同一键。
        if (body.has("prompt_cache_key")) return
        // Partition intentional model/account/endpoint changes without exposing a credential.
        val credential = java.security.MessageDigest.getInstance("SHA-256")
            .digest(config.apiKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val identity = listOf(config.providerId, config.baseUrl, config.model, config.authMode,
            credential, request.purpose.name, request.sessionId).joinToString("\n")
        body.put("prompt_cache_key", "eta-${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}")
    }
}
