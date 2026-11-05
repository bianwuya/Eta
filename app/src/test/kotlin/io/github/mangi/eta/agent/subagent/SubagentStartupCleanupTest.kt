package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentStartupCleanupTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
    private val call = AgentModelClient.ToolCall("call", "subagent", """{"prompt":"任务","description":"检查"}""")

    private fun children(parent: AgentRunController): Int {
        val field = AgentRunController::class.java.getDeclaredField("children").apply { isAccessible = true }
        return (field.get(parent) as Set<*>).size
    }

    @Test fun progressFailureBeforeChildToolsStillClosesParentBinding() {
        val parent = AgentRunController()
        val executor = SubagentExecutor(conversationKey = "startup-progress", parentController = parent,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = { error("fixture progress error") })
        val result = executor.execute(1, call)
        assertTrue(result.content.contains("SUBAGENT_START_FAILED"))
        assertEquals(0, children(parent))
    }

    @Test fun startCallbackFailureClosesOwnedToolsAndParentBinding() {
        val parent = AgentRunController()
        val closed = AtomicBoolean(false)
        val owned = object : AgentModelClient.ToolExecutor, AutoCloseable {
            override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult = error("unexpected")
            override fun close() { closed.set(true) }
        }
        val executor = SubagentExecutor(conversationKey = "startup-callback", parentController = parent,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") }, childToolFactory = { owned },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        val result = executor.execute(1, call) { _, _ -> error("fixture callback error") }
        assertFalse(org.json.JSONObject(result.content).optBoolean("ok"))
        assertTrue(closed.get())
        assertEquals(0, children(parent))
    }
}
