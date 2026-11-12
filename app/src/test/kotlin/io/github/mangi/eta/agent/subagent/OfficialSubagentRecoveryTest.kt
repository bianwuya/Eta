package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OfficialSubagentRecoveryTest {
    @Test fun interruptedInstanceCannotResumeWithChangedAccountOrReplayItsTool() {
        SubagentSessionStore.initialize(RuntimeEnvironment.getApplication())
        val owner = "bound-recovery-${java.util.UUID.randomUUID()}"
        val original = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://fixture.invalid", apiKey = "fixture-a",
            model = "gpt-fixture", systemPrompt = "", terminalTools = true)
        var selected = original
        var calls = 0
        var tools = 0
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++calls == 1) return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "once")
                        .put("type", "function").put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
                throw IllegalStateException("fixture worker loss after committed tool")
            }
        }
        fun worker() = SubagentExecutor(owner, AgentRunController(), { selected }, { AgentToolCapabilities(rootAvailable = false) },
            AgentModelClient.ToolExecutor { tools++; AgentModelClient.ToolResult("committed result") }, SkillContext.EMPTY,
            AgentMemoryContext.DISABLED, JSONArray(), null, {}, providerFactory = { model })
        val launch = AgentModelClient.ToolCall("bound-launch", "subagent", """{"prompt":"inspect","description":"inspect","tools":["read_file"],"subagent_type":"fixture-role"}""")
        val failed = JSONObject(worker().execute(1, launch).content)
        val id = failed.getString("subagent_id")
        assertFalse(failed.getBoolean("ok"))
        assertEquals(1, tools)
        assertEquals(2, calls)
        SubagentSessionStore.clearMemoryForTest()
        selected = original.copy(apiKey = "fixture-b")
        val resume = AgentModelClient.ToolCall("bound-resume", "subagent", JSONObject().put("prompt", "continue")
            .put("description", "continue").put("resume", id).toString())
        val rejected = JSONObject(worker().execute(2, resume).content)
        assertFalse(rejected.getBoolean("ok"))
        assertTrue(rejected.getString("message").contains("原子代理模型或账号不可用"))
        assertEquals(2, calls)
        assertEquals(1, tools)
        assertFalse(SubagentSessionStore.get(owner, id)!!.running.get())
        assertTrue(SubagentSessionStore.get(owner, id)!!.history.any { it.content.contains("committed result") })
    }

    @Test fun restoredInstanceIncludesItsLastCommittedCompactionInsteadOfOriginalHistory() {
        SubagentSessionStore.initialize(RuntimeEnvironment.getApplication())
        val owner = "compaction-durable-${java.util.UUID.randomUUID()}"
        val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://fixture.invalid", apiKey = "fixture",
            model = "gpt-fixture", systemPrompt = "", terminalTools = true, contextWindow = 1000, autoCompactionEnabled = true)
        var round = 0
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose == ProviderRequestPurpose.COMPACTION)
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "saved summary").put("finish_reason", "stop"))
                if (++round == 1) {
                    onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 900)))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(JSONObject().put("id", "read").put("type", "function")
                            .put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
                }
                throw IllegalStateException("fixture abrupt worker loss")
            }
        }
        val worker = SubagentExecutor(owner, AgentRunController(), { config }, { AgentToolCapabilities(rootAvailable = false) },
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("old history ".repeat(2000)) }, SkillContext.EMPTY,
            AgentMemoryContext.DISABLED, JSONArray(), null, {}, providerFactory = { model },
            compactionTarget = { AgentCompactionTarget(config.copy(contextWindow = 1000000), model) })
        var id = ""
        val launch = AgentModelClient.ToolCall("durable-compaction", "subagent", """{"prompt":"inspect","description":"inspect","tools":["read_file"],"subagent_type":"fixture-role"}""")
        worker.execute(1, launch) { child, _ -> id = child }
        val before = SubagentSessionStore.get(owner, id)!!.history
        assertTrue(before.any { it.contextSummary })
        SubagentSessionStore.clearMemoryForTest()
        val restored = SubagentSessionStore.get(owner, id)!!.history
        assertTrue(restored.any { it.contextSummary })
        assertFalse(restored.any { it.content.contains("old history old history") })
    }

    @Test fun twoChildrenKeepDistinctCacheIdentityAcrossFiveTurnsAndMemoryRecreation() {
        SubagentSessionStore.initialize(RuntimeEnvironment.getApplication())
        val conversation = "recovery-${java.util.UUID.randomUUID()}"
        val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "gpt-fixture", systemPrompt = "")
        val requests = linkedMapOf<String, MutableList<List<String>>>()
        val keys = linkedMapOf<String, MutableSet<String>>()
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, true)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val wire = OpenAiRequestMessages.forChatCompletions(request.messages)
                requests.getOrPut(request.sessionId) { mutableListOf() }.add((0 until wire.length()).map { wire.getJSONObject(it).toString() })
                keys.getOrPut(request.sessionId) { mutableSetOf() }.add(JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "verified result").put("finish_reason", "stop"))
            }
        }
        fun worker() = SubagentExecutor(conversation, AgentRunController(), { config }, { AgentToolCapabilities(rootAvailable = false) },
            AgentModelClient.ToolExecutor { error("unexpected tool") }, SkillContext.EMPTY, AgentMemoryContext.DISABLED,
            JSONArray(), null, {}, providerFactory = { model })
        fun call(n: Int, resume: String? = null): AgentModelClient.ToolCall = AgentModelClient.ToolCall("call-$n-${resume.orEmpty()}", "subagent",
            JSONObject().put("prompt", "next-$n").put("description", "fixture").also { if (resume != null) it.put("resume", resume) }.toString())
        val ids = List(2) { i -> JSONObject(worker().execute(1, call(i)).content).getString("subagent_id") }
        assertNotEquals(ids.first(), ids.last())
        repeat(4) { n ->
            if (n == 1) SubagentSessionStore.clearMemoryForTest()
            ids.forEach { id -> assertTrue(JSONObject(worker().execute(n + 2, call(n + 2, id)).content).getBoolean("ok")) }
        }
        assertEquals(2, keys.size)
        assertNotEquals(keys.values.first().single(), keys.values.last().single())
        requests.values.forEach { rounds ->
            assertEquals(5, rounds.size)
            rounds.zipWithNext().forEach { (old, new) -> assertEquals(old, new.take(old.size)) }
        }
        keys.values.forEach { assertEquals(1, it.size) }
        ids.forEach { assertFalse(SubagentSessionStore.get(conversation, it)!!.running.get()) }
    }
}
