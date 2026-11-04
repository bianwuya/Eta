package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MultimodalSteeringCacheTest {
    private val image = AgentModelClient.ModelImage("data:image/png;base64,aW1hZ2U=", "image/png", 5, source = "user_attach")
    private fun reply(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    @Test fun imagesAppendAtTurnBoundaryWithoutChangingPrefixCacheKeyOrSession() {
        for (text in listOf("检查这张图", "")) {
            val control = AgentRunController()
            val cancelled = AtomicInteger()
            control.register { cancelled.incrementAndGet() }
            val requests = mutableListOf<ProviderRequest>()
            val prefixes = mutableListOf<List<String>>()
            val cacheKeys = mutableListOf<String>()
            val provider = object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    requests += request
                    prefixes += (0 until request.messages.length()).map { request.messages.getJSONObject(it).toString() }
                    cacheKeys += JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key")
                    if (requests.size == 1) {
                        assertTrue(control.steer(text, listOf(image)))
                        return reply("第一轮完整结果")
                    }
                    val content = request.messages.getJSONObject(request.messages.length() - 1).getJSONArray("content")
                    assertEquals(image.reference, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                    assertTrue(content.getJSONObject(0).getString("text").contains("用户补充指令"))
                    return reply("完成图像补充")
                }
            }
            val result = AgentModelClient.complete(
                config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid", apiKey = "fixture", model = "gpt-fixture", systemPrompt = "固定系统约束"),
                prompt = "原始任务", history = listOf(AgentModelClient.ConversationMessage("user", "旧问题"), AgentModelClient.ConversationMessage("assistant", "旧回答")),
                toolExecutor = AgentModelClient.ToolExecutor { error("not expected") }, provider = provider, runController = control,
                sessionId = "stable-conversation",
            )
            assertEquals(2, requests.size)
            assertEquals(prefixes[0], prefixes[1].take(prefixes[0].size))
            assertEquals(requests[0].tools.toString(), requests[1].tools.toString())
            assertEquals(requests[0].sessionId, requests[1].sessionId)
            assertEquals(cacheKeys[0], cacheKeys[1])
            assertEquals(0, cancelled.get())
            assertEquals("完成图像补充", result.content)
            assertEquals(listOf("assistant", "user", "assistant"), result.transcript.map { it.role })
            val supplement = result.transcript.single { it.role == "user" }
            // Existing durable transcript privacy must not change to make live steering work.
            assertFalse(AgentConversationCodec.encodeTranscriptForStorage(listOf(supplement)).contains(image.reference))
            assertTrue(supplement.contentJson.contains("图片观察"))
        }
    }

    @Test fun imageSteeringWaitsForCompletedToolBatch() {
        val controller = AgentRunController()
        val executed = mutableListOf<String>()
        var rounds = 0
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++rounds == 1) return ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                    .put("tool_calls", org.json.JSONArray().also { calls -> (1..2).forEach { calls.put(JSONObject().put("id", "call-$it").put("type", "function")
                        .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))) } }))
                assertEquals(listOf("call-1", "call-2"), executed)
                val suffix = (request.messages.length()-4 until request.messages.length()).map { request.messages.getJSONObject(it).getString("role") }
                assertEquals(listOf("assistant", "tool", "tool", "user"), suffix)
                assertEquals(image.reference, request.messages.getJSONObject(request.messages.length()-1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
                return reply("done")
            }
        }
        AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), "start",
            AgentModelClient.ToolExecutor { call -> executed += call.id; if (call.id == "call-1") controller.steer("图", listOf(image)); AgentModelClient.ToolResult("{\"ok\":true}") },
            provider = provider, runController = controller)
        assertEquals(2, rounds)
    }
}
