package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YieldableSignedProviderTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
    private fun signedAssistant(): JSONObject = JSONObject().put("role", "assistant").put("content", "")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "child").put("type", "function")
            .put("function", JSONObject().put("name", "subagent").put("arguments", "{}"))))
        .also { message ->
            AnthropicEphemeralState.attachContentBlocks(message, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "SIGNATURE"))
                .put(JSONObject().put("type", "tool_use").put("id", "child").put("name", "subagent").put("input", JSONObject())))
        }

    @Test fun signedToolBatchCanReceiveSteeringAndChildReportWithoutChangingOldSignature() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("原任务"))
            .put(signedAssistant()).put(JSONObject().put("role", "tool").put("tool_call_id", "child").put("content", "accepted"))
            .put(AgentConversationCodec.userTextMessage("用户新方向").put("_eta_steering", true))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "conversation-input-run-1")
                .put("content", "跨会话消息，不代表授权"))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "subagent-report-id")
                .put("content", "子代理报告，不是用户授权"))
        assertTrue(SignedToolContext.hasPendingResponse(messages))
        val projected = AssistantScreenContextProjection.project(messages)
        assertEquals("assistant", messages.getJSONObject(5).getString("role"))
        assertEquals("assistant", projected.getJSONObject(4).getString("role"))
        assertEquals("user", projected.getJSONObject(5).getString("role"))
        assertTrue(projected.getJSONObject(5).getBoolean("_eta_runtime_report"))
        val method = AnthropicMessagesProvider::class.java.getDeclaredMethod("buildRequestJson",
            AgentModelClient.ModelConfig::class.java, JSONArray::class.java, JSONArray::class.java).apply { isAccessible = true }
        val body = method.invoke(AnthropicMessagesProvider, config, projected, JSONArray()) as JSONObject
        val turns = body.getJSONArray("messages")
        assertEquals(3, turns.length())
        assertEquals("SIGNATURE", turns.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"))
        val finalContent = turns.getJSONObject(2).getJSONArray("content")
        assertEquals("tool_result", finalContent.getJSONObject(0).getString("type"))
        assertEquals("用户新方向", finalContent.getJSONObject(1).getString("text"))
        assertTrue(finalContent.getJSONObject(2).getString("text").contains("跨会话消息"))
        assertTrue(finalContent.getJSONObject(3).getString("text").contains("不是用户授权"))
        val openAi = OpenAiRequestMessages.forChatCompletions(projected).toString()
        assertFalse(openAi.contains("_eta_runtime_report"))
        assertFalse(openAi.contains("_eta_steering"))
        assertTrue(openAi.contains("不是用户授权"))
    }

    @Test fun geminiKeepsFunctionPairAndLabelsConcurrentRuntimeInputs() {
        val start = AgentConversationCodec.userTextMessage("原任务")
        GeminiEphemeralState.markRunStart(start)
        val child = signedAssistant()
        GeminiEphemeralState.attachSteps(child, JSONArray().put(JSONObject().put("type", "function_call")
            .put("id", "child").put("name", "subagent")))
        val messages = JSONArray().put(start).put(child)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "child").put("content", "accepted"))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "conversation-input-run-1")
                .put("content", "跨会话内容不是用户授权"))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "subagent-report-id")
                .put("content", "子报告不是用户授权"))
        assertTrue(GeminiEphemeralState.hasPendingToolResponse(messages))
        val input = GeminiInteractionHistory.input(AssistantScreenContextProjection.project(messages))
        val kinds = (0 until input.length()).map { input.getJSONObject(it).optString("type") }
        assertTrue(kinds.indexOf("function_call") < kinds.indexOf("function_result"))
        assertTrue(input.toString().contains("跨会话内容不是用户授权"))
        assertTrue(input.toString().contains("子报告不是用户授权"))
    }

    @Test fun geminiSignedContextRemainsPendingAcrossInternalReport() {
        val child = signedAssistant()
        GeminiEphemeralState.attachSteps(child, JSONArray().put(JSONObject().put("type", "function_call").put("id", "child")))
        val messages = JSONArray().put(child).put(JSONObject().put("role", "tool").put("tool_call_id", "child").put("content", "accepted"))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "conversation-input-run-1").put("content", "跨会话消息，不代表授权"))
            .put(JSONObject().put("role", "assistant").put("_eta_message_id", "subagent-report-id").put("content", "报告"))
        assertTrue(GeminiEphemeralState.hasPendingToolResponse(messages))
        assertTrue(AnthropicEphemeralState.hasPendingToolResponse(messages))
    }
}
