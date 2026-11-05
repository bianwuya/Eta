package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentParentDirectiveTest {
    @Test
    fun parentRevisionIsDeliveredAtNextTurnAsInternalMessage() {
        val child = AgentRunController()
        val requests = mutableListOf<JSONArray>()
        val rounds = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "fake-provider"
            override val capabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = true,
                streamingToolCalls = true,
                imageInput = false,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += JSONArray(request.messages.toString())
                return when (rounds.incrementAndGet()) {
                    1 -> {
                        assertTrue(child.steerFromParent("放弃原来的目录搜索，改查日志"))
                        ProviderResponse(JSONObject().put("role", "assistant")
                            .put("content", "旧方向结果").put("finish_reason", "stop"))
                    }
                    2 -> ProviderResponse(JSONObject().put("role", "assistant")
                        .put("content", "已按新方向核对日志").put("finish_reason", "stop"))
                    else -> error("Unexpected model request: the directive must not trigger polling")
                }
            }
        }

        val response = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid",
                apiKey = "fixture",
                model = "fixture",
                systemPrompt = "",
            ),
            prompt = "检查项目",
            toolExecutor = AgentModelClient.ToolExecutor { error("No tools expected") },
            provider = provider,
            runController = child,
        )

        assertEquals("已按新方向核对日志", response.content)
        assertEquals(2, rounds.get())
        val second = requests.last()
        val injected = (0 until second.length()).map { second.getJSONObject(it) }
            .first { it.optBoolean("_eta_parent_agent_directive") }
        assertEquals("assistant", injected.getString("role"))
        assertTrue(injected.getString("content").contains("改查日志"))
        assertTrue(injected.getString("content").contains("不是终端用户发言"))
        assertFalse(child.hasPendingParentDirective)
    }
}
