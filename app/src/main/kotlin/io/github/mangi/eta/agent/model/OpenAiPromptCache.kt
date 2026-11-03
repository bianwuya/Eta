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
        val identity = "${config.providerId}\n${request.purpose.name}\n${request.sessionId}"
        body.put("prompt_cache_key", "eta-${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}")
    }
}
