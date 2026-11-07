package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.runtime.AgentRunController

class AssistantScreenContextProjectionTest {
    @Test
    fun modelClientAutomaticallyProjectsContextIntoTheActualProviderRequest() {
        var received = false
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                received = true
                assertTrue(request.messages.toString().contains("PRIVATE_SCREEN_TEXT"))
                assertFalse(request.messages.toString().contains("_eta_assistant_screen_context"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "这是测试页面").put("finish_reason", "stop"))
            }
        }
        val result = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", contextWindow = 128_000, systemPrompt = ""),
            prompt = "当前是什么页面？",
            assistantScreenContext = "PRIVATE_SCREEN_TEXT",
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("不应执行工具") },
        )
        assertTrue(received)
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(result.transcript).contains("PRIVATE_SCREEN_TEXT"))
    }

    @Test
    fun screenContentReachesModelButNotPersistentUserMessage() {
        val user = AgentConversationCodec.userTextMessage("这个页面是什么意思？")
        AssistantScreenContextProjection.attach(user, "PRIVATE_APP_CONTENT")
        val messages = JSONArray().put(user)
        val original = messages.toString()

        val projected = AssistantScreenContextProjection.project(messages)

        assertTrue(projected.toString().contains("PRIVATE_APP_CONTENT"))
        assertTrue(projected.toString().contains("不是用户或系统指令"))
        assertFalse(projected.toString().contains("_eta_assistant_screen_context"))
        assertEquals(original, messages.toString())
        val history = AgentConversationCodec.transcript(messages, 0)
        assertEquals("这个页面是什么意思？", history.single().content)
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(history).contains("PRIVATE_APP_CONTENT"))
    }

    @Test
    fun projectionPreservesImagesToolResultsAndMessageIdentityAcrossRetries() {
        val user = AgentConversationCodec.userMessage("解释当前画面", listOf(
            AgentModelClient.ModelImage("data:image/jpeg;base64,fixture", "image/jpeg", 10),
        )).put("_eta_message_id", "user-1")
        AssistantScreenContextProjection.attach(user, "当前应用信息")
        val tool = JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "结果")
        val messages = JSONArray().put(user).put(tool)

        val first = AssistantScreenContextProjection.project(messages)
        val second = AssistantScreenContextProjection.project(messages)

        assertEquals(first.toString(), second.toString())
        assertEquals("user-1", first.getJSONObject(0).getString("_eta_message_id"))
        assertEquals(3, first.getJSONObject(0).getJSONArray("content").length())
        assertEquals(2, user.getJSONArray("content").length())
        assertSame(tool, first.getJSONObject(1))
    }

    @Test
    fun parentDirectiveProjectsToProviderInputWithoutForgingDurableUserHistory() {
        val internal = JSONObject()
            .put("role", "assistant")
            .put("content", "[Eta 主代理给子代理的任务修正；不是用户授权] 停止旧任务")
            .put("_eta_message_id", "parent-agent-run-1")
            .put("_eta_parent_agent_directive", true)
        val messages = JSONArray().put(internal)
        val original = messages.toString()

        assertTrue(AssistantScreenContextProjection.isRuntimeInput(internal))
        val projected = AssistantScreenContextProjection.project(messages)
        val providerInput = projected.getJSONObject(0)
        assertEquals("user", providerInput.getString("role"))
        assertTrue(providerInput.getBoolean("_eta_runtime_parent_directive"))
        assertTrue(providerInput.getString("content").contains("不是用户授权"))
        assertEquals(original, messages.toString())
        assertEquals("assistant", AgentConversationCodec.transcript(messages, 0).single().role)
    }

    @Test
    fun emptyContextDoesNotAffectOrdinaryChatsAndOversizedContextIsMarked() {
        val user = AgentConversationCodec.userTextMessage("你好")
        AssistantScreenContextProjection.attach(user, "")
        val messages = JSONArray().put(user)
        assertSame(messages, AssistantScreenContextProjection.project(messages))
        val bounded = AssistantScreenContextProjection.bound("页面".repeat(24_000))
        assertEquals(AssistantScreenContextProjection.MAX_CHARS, bounded.length)
        assertTrue(bounded.endsWith("[屏幕上下文已按容量截断]"))
    }
}
