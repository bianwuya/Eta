package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class OfficialContinuityTest {
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://relay.invalid/v1", apiKey = "fixture-key", model = "gpt-fixture", systemPrompt = "stable fixture rules")
    private fun key(request: ProviderRequest): String = JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun fiveIndependentRunsPreserveRequestPrefixToolsAndCacheIdentity() {
        var history = emptyList<AgentModelClient.ConversationMessage>()
        var previousMessages = emptyList<String>()
        val tools = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        repeat(5) { turn ->
            val provider = object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, true)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    val messages = (0 until request.messages.length()).map { request.messages.getJSONObject(it).toString() }
                    assertEquals(previousMessages, messages.take(previousMessages.size))
                    previousMessages = messages
                    keys += key(request)
                    tools += digest(request.tools.toString())
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "reply-$turn").put("finish_reason", "stop"))
                }
            }
            val reply = AgentModelClient.complete(config = config, prompt = "turn-$turn", provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor { error("no tool expected") }, history = history,
                sessionId = "one-logical-conversation", operationId = "run-$turn")
            history = history + AgentModelClient.ConversationMessage("user", "turn-$turn", messageId = "user-run-$turn") + reply.transcript
        }
        assertEquals(1, tools.size)
        assertEquals(1, keys.size)
    }

    @Test fun intentionalModelAccountAndEndpointChangesIsolateCacheRouting() {
        fun request(c: AgentModelClient.ModelConfig) = ProviderRequest(c, JSONArray(), JSONArray(), "same-session")
        val original = key(request(config))
        assertNotEquals(original, key(request(config.copy(model = "gpt-other"))))
        assertNotEquals(original, key(request(config.copy(apiKey = "different-fixture-account"))))
        assertNotEquals(original, key(request(config.copy(baseUrl = "https://other.invalid/v1"))))
        assertEquals(original, key(request(config.copy())))
    }

    @Test fun oldPlanHandoffRetainsConversationAndSupplementsWithoutReenablingPlan() {
        val old = """{"type":"agent_ui_handoff","version":2,"conversationId":"chat","requestMode":"plan","planLineageId":"old","supplements":[{"index":1,"text":"saved input","createdAt":10}]}"""
        val migrated = AgentUiHandoffPayload.from(old)
        assertEquals("chat", migrated.conversationId)
        assertEquals("saved input", migrated.supplements.single().text)
        assertEquals("normal", migrated.requestMode)
        assertEquals("", migrated.planLineageId)
        assertFalse(migrated.toJson().contains("planLineageId"))
    }
}
