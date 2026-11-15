package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** Ordered start-only candidates. A candidate list never grants another account or tool permission. */
internal object SubagentModelPolicy {
    data class Candidate(val config: AgentModelClient.ModelConfig, val toolsSupported: Boolean = true,
        val enabled: Boolean = true)

    fun eligible(candidates: List<Candidate>, minimumContext: Int = 0): List<AgentModelClient.ModelConfig> {
        val first = candidates.firstOrNull()?.config ?: return emptyList()
        return candidates.filter { candidate ->
            val c = candidate.config
            candidate.enabled && candidate.toolsSupported &&
                c.providerId == first.providerId && c.baseUrl == first.baseUrl &&
                c.authMode == first.authMode && c.apiKey == first.apiKey &&
                c.providerType == first.providerType && c.openAiEndpointMode == first.openAiEndpointMode &&
                (minimumContext <= 0 || (c.knownContextWindow ?: 0) >= minimumContext)
        }.map { it.config }.distinctBy { it.model }
    }

    fun provider(candidates: List<AgentModelClient.ModelConfig>,
        factory: (AgentModelClient.ModelConfig) -> AgentProviderClient,
        selected: (AgentModelClient.ModelConfig, String?) -> Unit,
    ): AgentProviderClient {
        require(candidates.isNotEmpty()) { "没有可用的子代理候选模型" }
        val candidateModels = candidates.map { it.model }
        return object : AgentProviderClient {
            private var index = 0
            private var started = false
            private var delegate = factory(candidates.first())
            private var pinnedBinding: io.github.mangi.eta.agent.auth.ProviderCredentialBinding? = null
            private var bindingResolved = false
            override val id get() = delegate.id
            override val capabilities get() = delegate.capabilities
            override val activeModelConfig get() = candidates[index]
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose != io.github.mangi.eta.agent.model.ProviderRequestPurpose.CHAT) {
                    val effectiveConfig = if (index > 0 && request.config == candidates.first()) {
                        candidates[index]
                    } else {
                        request.config
                    }
                    val sameAccountBinding = pinnedBinding?.takeIf {
                        effectiveConfig.providerId == candidates.first().providerId &&
                            effectiveConfig.authMode == candidates.first().authMode
                    }
                    val effectiveRequest = if (effectiveConfig == request.config && sameAccountBinding == null) {
                        request
                    } else {
                        request.copy(
                            config = effectiveConfig,
                            credentialBinding = sameAccountBinding ?: request.credentialBinding,
                        )
                    }
                    return factory(effectiveConfig).complete(effectiveRequest, runController, onEvent)
                }
                if (!bindingResolved) {
                    pinnedBinding = request.accountBinding
                    bindingResolved = true
                }
                while (true) {
                    runController.throwIfCancelled()
                    val config = candidates[index]
                    val effectiveMessages = if (index > 0) {
                        replaceSystemModelIdentity(request.messages, candidateModels, config.model)
                    } else {
                        request.messages
                    }
                    var callbackFailed = false
                    try {
                        val response = delegate.complete(
                            request.copy(config = config, messages = effectiveMessages, credentialBinding = pinnedBinding),
                            runController,
                        ) { event ->
                            // Once output or a hosted tool begins, outcome is no longer known to be empty.
                            if (event !is ProviderEvent.RequestStarted && event !is ProviderEvent.ResponseHeaders) started = true
                            try { onEvent(event) } catch (failure: Throwable) { callbackFailed = true; throw failure }
                        }
                        started = true
                        return response
                    } catch (failure: AgentModelFailure) {
                        // HTTP status is explicit rejection before execution; transient429 stays in ModelRetry.
                        val rejected = failure.code in setOf("HTTP_401", "HTTP_403", "HTTP_404", "PROVIDER_AUTH_FAILED", "API_KEY_INVALID", "API_KEY_EXPIRED") ||
                            (failure.code == "HTTP_429" && !failure.retryable)
                        if (callbackFailed || started || !rejected || index == candidates.lastIndex) throw failure
                        index += 1
                        delegate = factory(candidates[index])
                        selected(candidates[index], failure.code)
                    }
                }
            }
        }
    }

    private fun replaceSystemModelIdentity(
        messages: JSONArray,
        candidateModels: List<String>,
        activeModel: String,
    ): JSONArray {
        val replacement = "当前配置的模型：${JSONObject.quote(activeModel)}。"
        val prefixes = candidateModels.asSequence()
            .distinct()
            .filter { it != activeModel }
            .map { "当前配置的模型：${JSONObject.quote(it)}。" }
            .toList()
        if (prefixes.isEmpty()) return messages
        var changed = false
        val copy = JSONArray()
        for (i in 0 until messages.length()) {
            val raw = messages.opt(i)
            val message = raw as? JSONObject
            if (message != null && message.optString("role") == "system") {
                val original = message.optString("content")
                var updated = original
                for (prefix in prefixes) {
                    if (updated.contains(prefix)) {
                        updated = updated.replace(prefix, replacement)
                    }
                }
                if (updated != original) {
                    changed = true
                    copy.put(JSONObject(message.toString()).put("content", updated))
                    continue
                }
            }
            copy.put(raw)
        }
        return if (changed) copy else messages
    }
}
