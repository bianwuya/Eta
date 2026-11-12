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
