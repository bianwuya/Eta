package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentModelPolicyTest {
    private val top = AgentModelClient.ModelConfig(providerId = "relay", baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "gpt-high", systemPrompt = "", contextWindow = 10000)
    private val next = top.copy(model = "gpt-low")
    private fun provider(script: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = script(request, onEvent)
    }
    private fun response() = ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))

    @Test fun failoverAndLaterTurnsKeepTheOriginalAccountBinding() {
        val first = io.github.mangi.eta.agent.auth.ProviderCredentialBinding("relay", "fixture", 1, "fixture-account-a")
        val changed = first.copy(generation = 2, credentialSlotId = "fixture-account-b")
        val bindings = mutableListOf<io.github.mangi.eta.agent.auth.ProviderCredentialBinding?>()
        val client = SubagentModelPolicy.provider(listOf(top, next), { config -> provider { request, _ ->
            bindings += request.accountBinding
            if (config.model == top.model) throw AgentModelFailure("HTTP_404", false, "fixture unavailable model")
            response()
        } }) { _, _ -> }
        client.complete(ProviderRequest(top, JSONArray(), JSONArray(), "child", credentialBinding = first), AgentRunController())
        client.complete(ProviderRequest(top, JSONArray(), JSONArray(), "child", credentialBinding = changed), AgentRunController())
        assertEquals(listOf(first, first, first), bindings)
    }

    @Test fun independentCompactionConfigIsNeverOverriddenByChatCandidate() {
        val seen = mutableListOf<String>()
        val client = SubagentModelPolicy.provider(listOf(top, next), { _ -> provider { request, _ ->
            seen += request.config.model
            response()
        } }) { _, _ -> fail("compaction must not fail over") }
        val compact = top.copy(model = "summary-model")
        client.complete(ProviderRequest(compact, JSONArray(), JSONArray(), "child", ProviderRequestPurpose.COMPACTION), AgentRunController())
        assertEquals(listOf("summary-model"), seen)
    }

    @Test fun callbackFailureCannotBeMistakenForAnUnavailableModel() {
        var factories = 0
        val client = SubagentModelPolicy.provider(listOf(top, next), { _ ->
            factories++
            provider { _, emit -> emit(ProviderEvent.RequestStarted); response() }
        }) { _, _ -> fail("callback persistence failure must not change model") }
        assertThrows(AgentModelFailure::class.java) {
            client.complete(ProviderRequest(top, JSONArray(), JSONArray(), "child"), AgentRunController()) {
                throw AgentModelFailure("HTTP_401", false, "fixture callback failure")
            }
        }
        assertEquals(1, factories)
    }

    @Test fun filtersDisabledIncompatibleShortContextAndOtherAccountWhilePreservingOrder() {
        val candidates = listOf(SubagentModelPolicy.Candidate(top), SubagentModelPolicy.Candidate(next.copy(model = "disabled"), enabled = false),
            SubagentModelPolicy.Candidate(next.copy(model = "no-tools"), toolsSupported = false),
            SubagentModelPolicy.Candidate(next.copy(model = "short", contextWindow = 20)),
            SubagentModelPolicy.Candidate(next.copy(model = "other-account", apiKey = "other")), SubagentModelPolicy.Candidate(next))
        assertEquals(listOf("gpt-high", "gpt-low"), SubagentModelPolicy.eligible(candidates, 5000).map { it.model })
        assertEquals(listOf("gpt-low", "gpt-high"), SubagentModelPolicy.eligible(listOf(candidates.last(), candidates.first())).map { it.model })
    }

    @Test fun explicitStartRejectionFailsOverOnceAndSubsequentTurnsStayOnActualModel() {
        for (code in listOf("HTTP_401", "HTTP_403", "HTTP_404", "HTTP_429")) {
            val requests = mutableListOf<String>()
            val selections = mutableListOf<String>()
            val client = SubagentModelPolicy.provider(listOf(top, next), { config -> provider { request, _ ->
                requests += request.config.model
                if (config.model == top.model) throw AgentModelFailure(code, false, "fixture rejection")
                response()
            } }) { config, reason -> selections += "${config.model}:$reason" }
            val request = ProviderRequest(top, JSONArray(), JSONArray(), "child-identity")
            repeat(5) { client.complete(request, AgentRunController()) }
            assertEquals(listOf(top.model) + List(5) { next.model }, requests)
            assertEquals(listOf("${next.model}:$code"), selections)
        }
    }

    @Test fun transientFailureHostedToolUnknownTransportAndEarlierSuccessNeverFailOver() {
        for (kind in listOf("transient", "hosted", "unknown", "already-started")) {
            var factories = 0
            var calls = 0
            val client = SubagentModelPolicy.provider(listOf(top, next), { _ ->
                factories++
                provider { _, emit ->
                    calls++
                    if (kind == "already-started" && calls == 1) return@provider response()
                    if (kind == "hosted") emit(ProviderEvent.HostedToolStarted("write", "hosted-write"))
                    if (kind == "unknown") throw java.io.IOException("EOF")
                    throw AgentModelFailure(if (kind == "transient") "HTTP_429" else "HTTP_401", kind == "transient", "fixture")
                }
            }) { _, _ -> fail("unsafe failover") }
            val request = ProviderRequest(top, JSONArray(), JSONArray(), "child")
            if (kind == "already-started") client.complete(request, AgentRunController())
            assertThrows(Exception::class.java) { client.complete(request, AgentRunController()) }
            assertEquals(1, factories)
        }
    }
}
