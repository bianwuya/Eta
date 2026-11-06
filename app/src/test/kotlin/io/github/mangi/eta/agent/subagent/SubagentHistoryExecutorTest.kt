package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AssistantBlockKind
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubagentHistoryExecutorTest {
    @After fun close() = EtaDatabase.closeForTests()

    @Test fun configFailureGetsAReadableTerminalHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val executor = SubagentExecutor(
            conversationKey = "history-config-failure", parentController = AgentRunController(),
            configProvider = { error("fixture configuration failed") },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {}, detailStoreFactory = { store },
        )
        val call = AgentModelClient.ToolCall("history-config", "subagent", """{"prompt":"task","description":"test"}""")
        val result = executor.execute(1, call)
        assertEquals(result.content, "SUBAGENT_CONFIG_ERROR", JSONObject(result.content).getString("code"))
        val reopened = SubagentDetailStore(context)
        assertEquals(call.id, reopened.header(call.id)?.detailId)
        val entry = reopened.history(call.id).single()
        assertEquals("failed", entry.status)
        assertTrue(reopened.page(call.id, "history-${entry.id}", 0).orEmpty().contains("configuration failed"))
        store.clear(call.id)
    }

    @Test fun realExecutorPersistsStreamToolResultAndFailureBeforeReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val provider = object : AgentProviderClient {
            override val id = "history-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            var count = 0
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                count++
                if (count == 1) {
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "unshown-thinking"))
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "first reply"))
                    onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 1))
                    val call = JSONObject().put("id", "history-tool").put("type", "function")
                        .put("function", JSONObject().put("name", "read_file").put("arguments", """{"path":"fixture"}"""))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "first reply")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call)))
                }
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "reply before failure"))
                throw AgentModelFailure("FIXTURE_ERROR", false, "fixture failure")
            }
        }
        val executor = SubagentExecutor(
            conversationKey = "history-executor-test", parentController = AgentRunController(),
            configProvider = { AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
                model = "fixture", systemPrompt = "", terminalTools = true, autoCompactionEnabled = false) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true,"text":"real tool output"}""") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {},
            detailStoreFactory = { store }, providerFactory = { provider },
        )
        val call = AgentModelClient.ToolCall("history-executor", "subagent",
            """{"subagent_type":"coder","prompt":"fixture task","description":"fixture"}""")
        val result = executor.execute(1, call)
        assertFalse(JSONObject(result.content).getBoolean("ok"))
        val reopened = SubagentDetailStore(context)
        val entries = reopened.history(call.id)
        assertEquals(listOf("assistant", "tool", "assistant", "terminal"), entries.map { it.kind })
        suspend fun body(entry: SubagentHistoryEntry) = reopened.page(call.id, "history-${entry.id}", 0).orEmpty()
        assertEquals("first reply", body(entries[0]))
        assertTrue(body(entries[1]).contains("real tool output"))
        assertEquals("reply before failure", body(entries[2]))
        assertEquals("partial", entries[2].status)
        assertEquals("failed", entries[3].status)
        for (entry in entries) assertFalse(body(entry).contains("unshown-thinking"))
        store.clear(call.id)
    }
}
