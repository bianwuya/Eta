package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨账号时只降级不重放：普通 assistant/tool_calls 与工具结果必须保留。 */
class ResponsesAccountScopeTest {
    @Test
    fun sameAccountKeepsRawOutputItems() {
        val messages = history()

        val input = buildInput(messages, "slot-a")

        assertEquals("reasoning", input.getJSONObject(0).optString("type"))
        assertEquals("system-a-secret", input.getJSONObject(0).optString("encrypted_content"))
        assertEquals(2, input.length())
    }

    @Test
    fun differentAccountFallsBackToPortableHistory() {
        val messages = history()

        val input = buildInput(messages, "slot-b")

        val types = (0 until input.length()).map { input.getJSONObject(it).optString("type") }
        assertFalse(types.contains("reasoning"))
        assertEquals(listOf("message", "function_call", "function_call_output"), types)
        assertEquals("已完成第一步", input.getJSONObject(0).optString("content"))
        assertEquals("call-1", input.getJSONObject(1).optString("call_id"))
        assertEquals("ok", input.getJSONObject(2).optString("output"))
    }

    @Test
    fun unscopedItemsCannotReplayIntoACodexAccount() {
        val messages = history(scope = null)

        val input = buildInput(messages, "slot-b")

        assertEquals("message", input.getJSONObject(0).optString("type"))
        assertFalse(input.toString().contains("encrypted_content"))
    }

    @Test
    fun assistantHistoryCopiesTheAccountScope() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "已完成第一步")
        ResponsesEphemeralState.attachOutputItems(
            assistant,
            JSONArray().put(JSONObject().put("type", "reasoning")),
            "slot-a",
        )

        val history = AgentConversationCodec.assistantHistoryMessage(assistant, emptyList())

        assertNotNull(ResponsesEphemeralState.outputItems(history, "slot-a"))
        assertEquals(null, ResponsesEphemeralState.outputItems(history, "slot-b"))
    }

    private fun buildInput(messages: JSONArray, scope: String): JSONArray =
        ResponsesRequestBuilder.build(
            AgentModelClient.ModelConfig(
                providerId = "codex",
                baseUrl = "https://chatgpt.com/backend-api/codex",
                apiKey = "",
                model = "gpt-fixture",
                systemPrompt = "fixture",
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
            ),
            messages,
            JSONArray(),
            scope,
        ).getJSONArray("input")

    private fun history(scope: String? = "slot-a"): JSONArray {
        val outputItems = JSONArray()
            .put(JSONObject().put("type", "reasoning").put("encrypted_content", "system-a-secret"))
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "已完成第一步")
            .put(
                "tool_calls",
                JSONArray().put(JSONObject()
                    .put("id", "call-1")
                    .put("function", JSONObject().put("name", "terminal").put("arguments", "{}"))),
            )
        ResponsesEphemeralState.attachOutputItems(assistant, outputItems, scope)
        val tool = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", "call-1")
            .put("content", "ok")
        return JSONArray().put(assistant).put(tool)
    }
}
