package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController

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
                (minimumContext == 0 || (c.knownContextWindow ?: 0) >= minimumContext)
        }.map { it.config }.distinctBy { it.model }
    }

    fun provider(candidates: List<AgentModelClient.ModelConfig>,
        factory: (AgentModelClient.ModelConfig) -> AgentProviderClient,
        selected: (AgentModelClient.ModelConfig, String?) -> Unit,
    ): AgentProviderClient {
        require(candidates.isNotEmpty()) { "没有可用的子代理候选模型" }
        return object : AgentProviderClient {
            private var index = 0
            private var started = false
            private var delegate = factory(candidates.first())
            private var pinnedBinding: io.github.mangi.eta.agent.auth.ProviderCredentialBinding? = null
            private var bindingResolved = false
            override val id get() = delegate.id
            override val capabilities get() = delegate.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose != io.github.mangi.eta.agent.model.ProviderRequestPurpose.CHAT) {
                    return factory(request.config).complete(request, runController, onEvent)
                }
                if (!bindingResolved) {
                    pinnedBinding = request.accountBinding
                    bindingResolved = true
                }
                while (true) {
                    runController.throwIfCancelled()
                    val config = candidates[index]
                    if (index > 0) {
                        for (i in 0 until request.messages.length()) {
                            val message = request.messages.optJSONObject(i) ?: continue
                            if (message.optString("role") == "system") {
                                message.put("content", message.optString("content").replace(
                                    "当前配置的模型：${org.json.JSONObject.quote(candidates.first().model)}。",
                                    "当前配置的模型：${org.json.JSONObject.quote(config.model)}。"))
                            }
                        }
                    }
                    var callbackFailed = false
                    try {
                        val response = delegate.complete(request.copy(config = config, credentialBinding = pinnedBinding), runController) { event ->
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
}
