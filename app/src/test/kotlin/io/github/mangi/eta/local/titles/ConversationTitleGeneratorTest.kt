package io.github.mangi.eta.local.titles

import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.Settings
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleGeneratorTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid/v1",
        apiKey = "fixture", model = "fixture", systemPrompt = "ORIGINAL CHAT PREFIX",
        terminalTools = true, browserTools = true, hostedWebSearchEnabled = true,
        extraBodyJson = "{\"tool_choice\":\"required\"}")
    private var received: ProviderRequest? = null
    private fun provider(text: String, finish: String = "stop") = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            received = request
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", finish))
        }
    }
    @Test fun titleRequestIsToolFreeAndNeverMutatesChatConfig() {
        val binding = ProviderCredentialBinding("p", "fixture", 3, "fixed-slot")
        val result = ConversationTitleGenerator.generate(ConversationTitleGenerator.Source(config, binding),
            "修复图片", "图片链路已调整", AgentRunController(), provider("图片显示修复"))
        assertEquals("图片显示修复", result)
        val request = received!!
        assertEquals(ProviderRequestPurpose.CONVERSATION_TITLE, request.purpose)
        assertSame(binding, request.accountBinding)
        assertEquals(0, request.effectiveTools.length())
        assertFalse(request.effectiveConfig.hostedWebSearchEnabled)
        assertEquals("", request.effectiveConfig.extraBodyJson)
        assertEquals("ORIGINAL CHAT PREFIX", config.systemPrompt)
        assertTrue(config.terminalTools)
        assertFalse(request.config.terminalTools)
    }
    @Test fun responseMustBeCompleteAndShort() {
        assertNull(ConversationTitleGenerator.normalize("a\nb"))
        assertNull(ConversationTitleGenerator.normalize(""))
        assertNull(ConversationTitleGenerator.normalize("x".repeat(41)))
        assertNull(ConversationTitleGenerator.normalize("<thinking>hidden</thinking>"))
        assertEquals("中文标题", ConversationTitleGenerator.normalize("“中文标题”"))
        assertEquals("😀".repeat(40), ConversationTitleGenerator.normalize("😀".repeat(40)))
        assertTrue(runCatching { ConversationTitleGenerator.generate(ConversationTitleGenerator.Source(config, null),
            "用户", "回答", AgentRunController(), provider("不完整", "length")) }.isFailure)
    }
    @Test fun cancelledRequestDoesNotReachProvider() {
        val controller = AgentRunController().apply { cancel() }
        assertTrue(runCatching { ConversationTitleGenerator.generate(ConversationTitleGenerator.Source(config, null),
            "用户", "回答", controller, provider("标题")) }.isFailure)
        assertNull(received)
    }
    @Test fun oldSettingsEnableNewConversationNamingWithoutHistoryRewrite() {
        assertTrue(Json.decodeFromString<Settings>("{}").autoConversationTitle)
        assertFalse(Json.decodeFromString<Settings>("{\"autoConversationTitle\":false}").autoConversationTitle)
    }
}
