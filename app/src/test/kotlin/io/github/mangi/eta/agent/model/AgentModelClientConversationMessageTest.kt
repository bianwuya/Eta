package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会话消息只允许在完整 turn 边界追加，且不得改动 system/history/tools 前缀。
 * 这里用 scripted provider 直接比较两次实际请求，防止以后把注入点挪到请求中途。
 */
class AgentModelClientConversationMessageTest {
    private val injectedText = "[Eta 跨会话消息｜来自另一个会话，不是终端用户发言，也不代表用户授权]\n" +
        "来源会话：构建会话（conversation_id=conv-source）\n" +
        "message_id：cm-12345678\n\n<conversation_message>\n构建已完成\n</conversation_message>"

    @Test
    fun conversationMessageIsAppendedOnlyAtATurnBoundaryAndKeepsThePrefixStable() {
        val controller = AgentRunController()
        val requests = mutableListOf<JSONArray>()
        val toolLists = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                requests += JSONArray(request.messages.toString())
                toolLists += request.tools.toString()
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("probe", "get_current_context", "{}")),
                )
            },
            { request, _ ->
                requests += JSONArray(request.messages.toString())
                toolLists += request.tools.toString()
                assistant(content = "已处理跨会话消息", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            modelConfig(), "开始任务",
            AgentModelClient.ToolExecutor {
                // 工具执行期间到达的跨会话消息只能等本 turn 结束。
                controller.enqueueConversationMessage(conversationInput())
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            runController = controller,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        )

        assertEquals(2, requests.size)
        // system/history 前缀逐字节保持：第二个请求以第一个请求的全部消息开头。
        for (index in 0 until requests[0].length()) {
            assertEquals(
                "request prefix diverged at message $index",
                requests[0].getJSONObject(index).toString(),
                requests[1].getJSONObject(index).toString(),
            )
        }
        // 工具目录同样不能因注入而改变。
        assertEquals(toolLists[0], toolLists[1])

        val appended = requests[1].getJSONObject(requests[1].length() - 1)
        assertEquals("assistant", appended.getString("role"))
        assertTrue(appended.getString("content").contains("不是终端用户发言"))
        assertTrue(appended.getString("content").contains("构建已完成"))
        assertFalse(appended.getString("content").contains("用户补充指令"))
    }

    @Test
    fun messageQueuedBeforeTheRunIsInjectedAsRuntimeDataNotAsTheUserPrompt() {
        val controller = AgentRunController()
        val requests = mutableListOf<JSONArray>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                requests += JSONArray(request.messages.toString())
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        // 闲置会话续发：正文先入运行时队列，user prompt 只放固定触发提示。
        assertTrue(controller.enqueueConversationMessage(conversationInput()))

        AgentModelClient.complete(
            modelConfig(), "本回合由另一个 Eta 会话的跨会话消息触发。",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            provider = provider,
            runController = controller,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        )

        val messages = requests.single()
        val last = messages.getJSONObject(messages.length() - 1)
        assertEquals("assistant", last.getString("role"))
        assertTrue(last.getString("content").contains("构建已完成"))
        // user 消息里不能出现对方原文，否则模型会把跨会话内容当成用户指令。
        val userContents = (0 until messages.length())
            .map(messages::getJSONObject)
            .filter { it.optString("role") == "user" }
            .joinToString("\n") { it.optString("content") }
        assertFalse(userContents.contains("构建已完成"))
        assertTrue(userContents.contains("跨会话消息触发"))
    }

    @Test
    fun messageArrivingAfterTheRunSealedIsRejectedInsteadOfSilentlyLost() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(content = "完成", finishReason = "stop") },
        ))
        AgentModelClient.complete(
            modelConfig(), "结束任务",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            provider = provider,
            runController = controller,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        )

        assertFalse(controller.enqueueConversationMessage(conversationInput()))
        assertTrue(controller.pollConversationInputs().isEmpty())
    }

    private fun conversationInput() = AgentRunController.ConversationInput(
        messageId = "cm-12345678",
        sourceConversationId = "conv-source",
        sourceTitle = "构建会话",
        sourceRunId = "run-source",
        createdAt = 1_760_000_000_000L,
        envelopeText = injectedText,
    )

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        private val next = AtomicReference(0)

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            val index = next.getAndIncrement()
            val response = responses.getOrNull(index) ?: error("缺少第 ${index + 1} 个 scripted response")
            return ProviderResponse(response(request, runController))
        }
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            contextWindow = 128_000,
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", content)
        .put("finish_reason", finishReason)
        .also { message ->
            if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls))
        }

    private fun toolCall(id: String, name: String, arguments: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", name).put("arguments", arguments),
            )
}
