package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskHandoffIntegrationTest {
    private fun answer(text: String = "done") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun handoff(goal: String, constraint: String = "no writes") = JSONObject()
        .put("goal", goal).put("user_constraints", JSONArray().put(constraint))
    private class Fixture(val model: String = "independent-child") {
        val conversation = "handoff-${UUID.randomUUID()}"
        val parent = AgentRunController()
        val requests = mutableListOf<ProviderRequest>()
        var started = 0
        var factories = 0
        var tools = 0
        var script: (Int, ProviderRequest) -> ProviderResponse = { _, _ -> ProviderResponse(JSONObject()
            .put("role", "assistant").put("content", "done").put("finish_reason", "stop")) }
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests += request.copy(messages=JSONArray(request.messages.toString()), tools=JSONArray(request.tools.toString()))
                return script(requests.size, request)
            }
        }
        val executor = SubagentExecutor(conversationKey=conversation, parentController=parent,
            configProvider={ factories++; AgentModelClient.ModelConfig(baseUrl="https://example.invalid", apiKey="fixture",
                model=model, systemPrompt="parent provider system must not leak", terminalTools=true) },
            capabilitiesProvider={ AgentToolCapabilities(rootAvailable=false) },
            baseToolExecutor=AgentModelClient.ToolExecutor { tools++; AgentModelClient.ToolResult("DONE_SIDE_EFFECT") },
            skillContext=SkillContext(listOf(SkillIndexEntry("fixture-skill", "fixture", "SKILL_SENTINEL", rootPath="/fixture", skillFilePath="/fixture/SKILL.md", hasScripts=false, hasReferences=false, hasAssets=false, hasEvals=false))),
            memoryContext=AgentMemoryContext(true,"b".repeat(64),100,"# 核心记忆\nMEMORY_SENTINEL",false,"# 核心记忆",8000),
            mcpTools=JSONArray(),
            executionContext=null, onProgress={}, providerFactory={ provider })
        fun call(prompt: String, handoff: Any? = null, resume: String? = null): JSONObject {
            val args=JSONObject().put("prompt",prompt).put("description","fixture")
            if (handoff != null) args.put("handoff",handoff)
            if (resume == null) args.put("subagent_type","handoff-fixture").put("tools",JSONArray().put("read_file")) else args.put("resume",resume)
            return JSONObject(executor.execute(1,AgentModelClient.ToolCall(UUID.randomUUID().toString(),"subagent",args.toString())) { _, _ -> started++ }.content)
        }
        fun users(index: Int) = (0 until requests[index].messages.length()).map { requests[index].messages.getJSONObject(it) }
            .filter { it.getString("role")=="user" }.map { it.getString("content") }
        fun systems(index: Int) = (0 until requests[index].messages.length()).map { requests[index].messages.getJSONObject(it) }
            .filter { it.getString("role")=="system" }.map { it.getString("content") }
    }

    @Test fun legacyAndEmptyHandoffKeepOneUnchangedTaskAndOneModelRequest() {
        for (value in listOf(null,JSONObject.NULL,JSONObject(),JSONObject().put("known_facts",JSONArray()))) {
            val f=Fixture()
            try {
                assertTrue(f.call("original\n  exact code",value).getBoolean("ok"))
                assertEquals(listOf("original\n  exact code"),f.users(0))
                assertEquals(1,f.requests.size)
                assertEquals(1,f.started)
            } finally { f.parent.cancel() }
        }
    }

    @Test fun initialTaskContainsCompleteHandoffAsUserDataAndSharesExistingMemorySkillsAndModel() {
        for (model in listOf("openai-child","claude-child","gemini-child")) {
            val f=Fixture(model)
            try {
                assertTrue(f.call("inspect",handoff("GOAL_SENTINEL","TAIL_CONSTRAINT")).getBoolean("ok"))
                assertEquals(1,f.requests.size)
                assertEquals(1,f.users(0).size)
                assertTrue(f.users(0).single().contains("GOAL_SENTINEL"))
                assertTrue(f.users(0).single().endsWith("TAIL_CONSTRAINT"))
                assertTrue(f.systems(0).any { it.contains("MEMORY_SENTINEL") })
                assertTrue(f.systems(0).any { it.contains("SKILL_SENTINEL") })
                assertFalse(f.systems(0).any { it.contains("GOAL_SENTINEL") || it.contains("TAIL_CONSTRAINT") || it.contains("parent provider system must not leak") })
                assertEquals(model,f.requests.single().config.model)
                assertFalse(f.requests.single().config.subagentTools)
                val names=(0 until f.requests.single().effectiveTools.length()).map { f.requests.single().effectiveTools.getJSONObject(it).getJSONObject("function").getString("name") }
                assertEquals(listOf("read_file"),names)
            } finally { f.parent.cancel() }
        }
    }

    @Test fun successfulResumeAppendsOnlyNewCorrectionAndKeepsIdentitySystemAndToolsStable() {
        val f=Fixture()
        try {
            val first=f.call("inspect",handoff("OLD_GOAL","OLD_CONSTRAINT"))
            val id=first.getString("subagent_id")
            assertTrue(f.call("REVOKE OLD_CONSTRAINT; now only report",handoff("NEW_GOAL","NEW_CONSTRAINT"),id).getBoolean("ok"))
            assertEquals(2,f.requests.size)
            assertEquals(2,f.users(1).size)
            assertEquals(f.users(0).single(),f.users(1).first())
            assertFalse(f.users(1).last().contains("OLD_GOAL"))
            assertTrue(f.users(1).last().contains("REVOKE OLD_CONSTRAINT"))
            assertEquals(f.systems(0),f.systems(1))
            assertEquals(f.requests[0].tools.toString(),f.requests[1].tools.toString())
            assertEquals(f.requests[0].sessionId,f.requests[1].sessionId)
        } finally { f.parent.cancel() }
    }

    @Test fun failedRunRetainsHandoffAndCompletedToolResultWithoutRepeatingTheToolOnResume() {
        val f=Fixture()
        f.script={ round,_ -> when(round) {
            1 -> ProviderResponse(JSONObject().put("role","assistant").put("content","").put("finish_reason","tool_calls")
                .put("tool_calls",JSONArray().put(JSONObject().put("id","performed").put("type","function")
                    .put("function",JSONObject().put("name","read_file").put("arguments","{}")))))
            2 -> throw AgentModelFailure("FIXTURE_PERMANENT",false,"fixture failure after performed tool")
            else -> answer("resumed")
        } }
        try {
            val failed=f.call("inspect",handoff("ORIGINAL_GOAL","GUARD_BEFORE_FAILURE"))
            assertFalse(failed.getBoolean("ok"))
            val id=failed.getString("subagent_id")
            assertTrue(f.call("continue without repeating completed tools",resume=id).getBoolean("ok"))
            val tail=f.requests.last().messages.toString()
            assertTrue(tail.contains("GUARD_BEFORE_FAILURE"))
            assertTrue(tail.contains("DONE_SIDE_EFFECT"))
            assertEquals(1,f.tools)
            assertEquals(3,f.requests.size)
            assertEquals(1,f.users(2).count { it.contains("ORIGINAL_GOAL") })
        } finally { f.parent.cancel() }
    }

    @Test fun invalidHandoffNeverCreatesAnEntryLaunchesAProviderOrCallsATool() {
        val f=Fixture()
        try {
            for(value in listOf("bad",JSONObject().put("user_constraints",JSONArray().put(1)),
                JSONObject().put("goal","x".repeat(16001)))) {
                assertEquals("INVALID_HANDOFF",f.call("inspect",value).getString("code"))
            }
            assertEquals(0,f.requests.size);assertEquals(0,f.factories);assertEquals(0,f.started);assertEquals(0,f.tools)
            assertEquals("sub_1",f.call("valid").getString("subagent_id"))
        } finally { f.parent.cancel() }
    }

    @Test fun invalidResumeDoesNotClaimOrAlterExistingWorkingHistory() {
        val f=Fixture()
        try {
            val id=f.call("old",handoff("old-goal")).getString("subagent_id")
            assertEquals("INVALID_HANDOFF",f.call("rejected",JSONObject().put("goal",true),id).getString("code"))
            assertTrue(f.call("new",resume=id).getBoolean("ok"))
            assertEquals(2,f.requests.size)
            assertFalse(f.requests.last().messages.toString().contains("rejected"))
        } finally { f.parent.cancel() }
    }

    @Test fun parallelChildrenNeverReceiveAnotherChildsHandoffOrHistory() {
        val pool=Executors.newFixedThreadPool(2)
        val ready=CountDownLatch(2)
        val go=CountDownLatch(1)
        try {
            val tasks=listOf("ALPHA","BETA").map { marker -> pool.submit(Callable {
                val f=Fixture()
                f.script={ _, request -> ready.countDown(); assertTrue(go.await(5,TimeUnit.SECONDS))
                    assertTrue(request.messages.toString().contains(marker+"_GOAL"))
                    assertFalse(request.messages.toString().contains((if(marker=="ALPHA") "BETA" else "ALPHA")+"_GOAL"))
                    answer(marker)
                }
                try { f.call("inspect",handoff(marker+"_GOAL")) } finally { f.parent.cancel() }
            }) }
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown()
            assertEquals(listOf("ALPHA","BETA"),tasks.map { it.get(5,TimeUnit.SECONDS).getString("summary") })
        } finally { go.countDown();pool.shutdownNow() }
    }

    @Test fun handoffSchemaRemainsOptionalAndDoesNotReplaceLegacyOrControlFields() {
        val fn=AgentSubagentToolCatalog.schema(SubagentProfile.BUILTIN_PROFILES).getJSONObject("function")
        val parameters=fn.getJSONObject("parameters")
        assertEquals(listOf("prompt","description"),(0 until parameters.getJSONArray("required").length()).map { parameters.getJSONArray("required").getString(it) })
        val properties=parameters.getJSONObject("properties")
        for(name in listOf("prompt","description","resume","role","tools","subagent_type","handoff")) assertTrue(properties.has(name))
        assertFalse(properties.getJSONObject("handoff").has("required"))
        assertTrue(fn.getString("description").contains("no new user guidance is required"))
    }
}
