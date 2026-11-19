package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiPromptCacheTest {
    private fun request(session: String = "conversation-1", model: String = "gpt-fixture") = ProviderRequest(
        AgentModelClient.ModelConfig(
            providerId = "relay",
            baseUrl = "https://example.invalid/v1",
            apiKey = "fixture",
            model = model,
            systemPrompt = "",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        JSONArray(), JSONArray(), sessionId = session,
    )

    private fun key(request: ProviderRequest): String = JSONObject().also {
        OpenAiPromptCache.apply(it, request)
    }.optString("prompt_cache_key")

    @Test
    fun sameConversationKeepsLegacyKeyAcrossHistoryGrowthAndSeparateRequests() {
        val first = request()
        val next = request().copy(messages = JSONArray().put(JSONObject().put("role", "user").put("content", "继续")))
        assertEquals(LEGACY_CONVERSATION_KEY, key(first))
        assertEquals(key(first), key(next))
        assertNotEquals(key(first), key(request("conversation-2")))
        assertNotEquals(key(first), key(first.copy(purpose = ProviderRequestPurpose.COMPACTION)))
        assertNotEquals(key(first), key(first.copy(config = first.config.copy(providerId = "other-relay"))))
    }

    @Test
    fun responsesHttpRequestRestoresLegacyKeyForExistingConversation() {
        val first = OpenAiResponsesProvider.buildHttpRequest(request()).jsonBody()
        val next = OpenAiResponsesProvider.buildHttpRequest(
            request().copy(messages = JSONArray().put(JSONObject().put("role", "user").put("content", "继续"))),
        ).jsonBody()

        assertEquals(LEGACY_CONVERSATION_KEY, first.getString("prompt_cache_key"))
        assertEquals(first.getString("prompt_cache_key"), next.getString("prompt_cache_key"))
    }

    @Test
    fun explicitKeyIsPreservedAndUnrelatedModelsAndSubscriptionAreUnchanged() {
        val body = JSONObject().put("prompt_cache_key", "user-selected")
        OpenAiPromptCache.apply(body, request())
        assertEquals("user-selected", body.getString("prompt_cache_key"))
        assertEquals("", key(request(model = "deepseek-fixture")))
        val subscription = request().let { it.copy(config = it.config.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)) }
        assertEquals("", key(subscription))
    }

    private fun Request.jsonBody(): JSONObject {
        val buffer = Buffer()
        checkNotNull(body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private companion object {
        const val LEGACY_CONVERSATION_KEY = "eta-9e089e14-b003-3bd2-a772-ddc014a99056"
    }
}
