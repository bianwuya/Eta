package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_INTERACTIONS,
}

internal enum class ProviderRequestPurpose {
    CHAT, COMPACTION, REPLY_REWRITE, TRANSCRIPT_REFINE, CONVERSATION_TITLE;

    val allowsTools: Boolean get() = this == CHAT
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    /** 调用方已解析的账号绑定；为空时在首次使用处解析一次。 */
    val credentialBinding: ProviderCredentialBinding? = null,
    /** Reactive transport fallback for this request only; never persisted as a provider setting. */
    val forceHttp1: Boolean = false,
) {
    /**
     * 本次模型请求开始时固定的认证绑定：同一次请求的重试与 401 刷新都沿用它，
     * 因此 UI 切换账号不会让在途请求改用其他账号。
     */
    val accountBinding: ProviderCredentialBinding? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        credentialBinding ?: if (ProviderAuthModes.isOAuth(config.authMode)) {
            ProviderCredentialRepository.bindingOrNull(config.providerId, config.authMode)
        } else {
            null
        }
    }

    val effectiveConfig: AgentModelClient.ModelConfig get() {
        val connected = ProviderConnectionProfile.forAuthMode(config.authMode)?.let { profile ->
            config.copy(
                baseUrl = profile.baseUrl,
                providerType = profile.providerType,
                providerSourceType = profile.sourceType,
                openAiEndpointMode = profile.endpointMode,
                apiKey = "",
            )
        } ?: config
        return if (!purpose.allowsTools) {
            val allowed = if (purpose == ProviderRequestPurpose.COMPACTION) setOf(
                "temperature", "top_p", "top_k", "min_p", "max_tokens", "max_completion_tokens", "max_output_tokens",
                "thinking", "reasoning", "reasoning_effort", "verbosity", "service_tier", "provider", "transforms",
                "route", "routing", "enable_thinking", "chat_template_kwargs",
            ) else emptySet()
            val extra = if (allowed.isEmpty() || connected.extraBodyJson.isBlank()) "" else JSONObject(connected.extraBodyJson).let { body ->
                JSONObject().also { safe -> body.keys().forEach { key ->
                    if (key in allowed) safe.put(key, body.get(key))
                } }.toString()
            }
            connected.copy(hostedWebSearchEnabled = false, supportsSearchSuggestions = false,
                extraBodyJson = extra, customBody = connected.customBody.filter { it.key in allowed })
        } else connected
    }
    val effectiveTools: JSONArray get() = if (purpose.allowsTools) tools else JSONArray()
}

internal data class ProviderResponse(
    val assistantMessage: JSONObject
) {
    val stopReason: AssistantStopReason
        get() = AssistantStopReason.fromWireValue(assistantMessage.optString("finish_reason"))
}

internal enum class AssistantStopReason {
    END_TURN,
    TOOL_USE,
    OUTPUT_LIMIT,
    CONTENT_FILTER,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): AssistantStopReason =
            when (value?.trim()?.lowercase()) {
                "stop", "end_turn" -> END_TURN
                "tool_calls", "tool_use" -> TOOL_USE
                "length", "max_tokens" -> OUTPUT_LIMIT
                "content_filter", "refusal" -> CONTENT_FILTER
                else -> UNKNOWN
            }
    }
}

internal enum class AssistantBlockKind {
    TEXT,
    THINKING,
    TOOL_CALL,
}

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int
    ) : ProviderEvent

    data class BlockStart(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : ProviderEvent

    data class BlockDelta(
        val kind: AssistantBlockKind,
        val index: Int,
        val delta: String,
    ) : ProviderEvent

    data class BlockEnd(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val content: String = "",
        val replaceContent: Boolean = false,
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage,
        val contextInputTokens: Int? = usage.inputTokens,
    ) : ProviderEvent

    data class HostedToolStarted(
        val id: String,
        val name: String,
    ) : ProviderEvent

    data class HostedToolFinished(
        val id: String,
        val name: String,
        val success: Boolean,
    ) : ProviderEvent

    data class SearchSuggestions(
        val index: Int,
        val html: String,
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
