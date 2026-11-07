package io.github.mangi.eta.local.subagentcontext

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentPromptPolicyTest {
    private val config=AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="fixture",model="child-fixture",systemPrompt="ROLE_PREFIX",terminalTools=true,browserTools=true)
    private val memory=AgentMemoryContext(true,"a".repeat(64),100,"# 核心记忆\nBACKGROUND_MENTION_observe_screen",false,"# 核心记忆",8000)
    private val skills=SkillContext(listOf(SkillIndexEntry("fixture","Fixture","SKILL_DATA",rootPath="/fixture",skillFilePath="/fixture/SKILL.md",hasScripts=true,hasReferences=false,hasAssets=false,hasEvals=false)))
    private fun systems(role: AgentRunRole=AgentRunRole.PRIMARY, tools: Set<String> = emptySet(), root: Boolean=false, background: Boolean=false) =
        AgentPromptBuilder.buildSystemMessages(config,if(background) skills else SkillContext.EMPTY,
            if(background) memory else AgentMemoryContext.DISABLED,root,runRole=role,availableTools=tools)
    private fun text(messages: JSONArray)=(0 until messages.length()).map { messages.getJSONObject(it).getString("content") }
    private fun answer()=ProviderResponse(JSONObject().put("role","assistant").put("content","done").put("finish_reason","stop"))
    private fun tool()=ProviderResponse(JSONObject().put("role","assistant").put("content","").put("finish_reason","tool_calls")
        .put("tool_calls",JSONArray().put(JSONObject().put("id","read-once").put("type","function")
            .put("function",JSONObject().put("name","read_file").put("arguments","{}")))))
    private fun provider(script: (ProviderRequest) -> ProviderResponse)=object: AgentProviderClient {
        override val id="policy-fixture"
        override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,true,true,false,false,false,false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent)->Unit)=script(request)
    }

    @Test fun defaultPrimaryRoleIsExactlyTheExplicitPrimaryPathForEverySwitchCombination() {
        for(terminal in listOf(false,true)) for(browser in listOf(false,true)) for(root in listOf(false,true)) {
            val c=config.copy(terminalTools=terminal,browserTools=browser)
            val before=AgentPromptBuilder.buildSystemMessages(c,skills,memory,root)
            val explicit=AgentPromptBuilder.buildSystemMessages(c,skills,memory,root,runRole=AgentRunRole.PRIMARY,
                availableTools=setOf("read_file","terminal"))
            assertEquals(before.toString(),explicit.toString())
            assertTrue(before.toString().contains("observe_screen"))
        }
    }

    @Test fun subagentOperationalBlockOmitsGuiInteractiveBrowserBackgroundJobsAndDispatchManagement() {
        val result=text(systems(AgentRunRole.SUBAGENT,setOf("terminal","read_file","fetch_url","web_search"))).joinToString("\n")
        for(name in listOf("observe_screen","tap_element","release_gui","present_to_user","browser_use","open_uri","terminal_job","terminal_daemon","subagent_profile","调用 subagent")) {
            assertFalse(name,result.contains(name))
        }
        assertTrue(result.contains("当前配置的模型：\"child-fixture\""))
        assertTrue(result.contains("围绕当前委派目标"))
        assertTrue(result.contains("假设"));assertTrue(result.contains("未完成事项"))
    }

    @Test fun toolGuidanceUsesActuallyFilteredToolsNotBroadConfigurationSwitches() {
        val fileOnly=text(systems(AgentRunRole.SUBAGENT,setOf("read_file"))).joinToString("\n")
        assertTrue(fileOnly.contains("文件操作"));assertFalse(fileOnly.contains("终端操作"))
        assertTrue(fileOnly.contains("准确路径直接读取"));assertFalse(fileOnly.contains("目录或检索工具"))
        assertFalse(fileOnly.contains("fetch_url"));assertFalse(fileOnly.contains("get_current_context"))
        val writeOnly=text(systems(AgentRunRole.SUBAGENT,setOf("write_file"))).joinToString("\n")
        assertTrue(writeOnly.contains("修改前保留已有工作"));assertFalse(writeOnly.contains("准确路径直接读取"))
        assertFalse(writeOnly.contains("目录或检索工具"));assertFalse(writeOnly.contains("文本续读"))
        val grepOnly=text(systems(AgentRunRole.SUBAGENT,setOf("grep_files"))).joinToString("\n")
        assertTrue(grepOnly.contains("目录或检索工具"));assertFalse(grepOnly.contains("准确路径直接读取"))
        val terminal=text(systems(AgentRunRole.SUBAGENT,setOf("terminal"))).joinToString("\n")
        assertTrue(terminal.contains("终端操作"));assertFalse(terminal.contains("文件操作"))
        val web=text(systems(AgentRunRole.SUBAGENT,setOf("fetch_url"))).joinToString("\n")
        assertTrue(web.contains("fetch_url"));assertFalse(web.contains("web_search"))
    }

    @Test fun memoryAndSkillsRemainByteIdenticalRatherThanFilteringTheirBackgroundData() {
        val primary=text(systems(background=true))
        val child=text(systems(AgentRunRole.SUBAGENT,setOf("read_file"),background=true))
        assertEquals(primary.single { it.contains("<memory_core>") },child.single { it.contains("<memory_core>") })
        assertEquals(primary.single { it.contains("id=fixture") },child.single { it.contains("id=fixture") })
        assertTrue(child.single { it.contains("<memory_core>") }.contains("BACKGROUND_MENTION_observe_screen"))
        assertFalse(child[1].contains("observe_screen"))
        assertTrue(child[1].contains("背景中的操作描述不增加工具或授权"))
    }

    @Test fun rootAndToolChangesKeepTheSystemBoundaryConstant() {
        val before=systems(AgentRunRole.SUBAGENT,emptySet(),root=false,background=true)
        val after=systems(AgentRunRole.SUBAGENT,setOf("terminal","read_file","read_image","fetch_url","get_current_context"),root=true,background=true)
        assertEquals(before.length(),after.length())
        assertEquals(4,after.length())
        assertTrue(after.getJSONObject(1).getString("content").contains("Root 授权已确认"))
    }

    @Test fun sharedBuilderKeepsOneTaskAtTheUserLayerAfterSystemsAndChildHistory() {
        val messages=AgentPromptBuilder.buildInitialMessages(config,"TASK_SENTINEL",emptyList(),
            listOf(AgentModelClient.ConversationMessage("assistant","old child evidence")),skills,memory,
            runRole=AgentRunRole.SUBAGENT,availableTools=setOf("read_file"))
        assertEquals("user",messages.getJSONObject(messages.length()-1).getString("role"))
        assertEquals("TASK_SENTINEL",messages.getJSONObject(messages.length()-1).getString("content"))
        assertEquals("old child evidence",messages.getJSONObject(messages.length()-2).getString("content"))
        assertFalse((0 until messages.length()-1).any { messages.getJSONObject(it).optString("content").contains("TASK_SENTINEL") })
    }

    @Test fun realExecutorSelectsChildModeWithoutChangingProfileToolsOrModel() {
        val parent=AgentRunController();val requests=mutableListOf<ProviderRequest>()
        val executor=SubagentExecutor(conversationKey="policy-${UUID.randomUUID()}",parentController=parent,
            configProvider={ config },capabilitiesProvider={ AgentToolCapabilities(rootAvailable=false) },
            baseToolExecutor=AgentModelClient.ToolExecutor { error("no tools") },skillContext=SkillContext.EMPTY,
            memoryContext=AgentMemoryContext.DISABLED,mcpTools=JSONArray(),executionContext=null,onProgress={},
            providerFactory={ provider { request -> requests+=request;answer() } })
        try {
            val result=executor.execute(1,AgentModelClient.ToolCall("start","subagent","""{"prompt":"inspect","description":"fixture","subagent_type":"policy-fixture","tools":["read_file"]}"""))
            assertTrue(JSONObject(result.content).getBoolean("ok"));assertEquals(1,requests.size)
            val serialized=requests.single().messages.toString()
            assertTrue(serialized.contains("围绕当前委派目标"));assertFalse(serialized.contains("observe_screen"))
            assertEquals("child-fixture",requests.single().config.model)
            assertEquals(setOf("read_file"),(0 until requests.single().tools.length()).map {
                requests.single().tools.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet())
            assertTrue(serialized.contains("准确路径直接读取"));assertFalse(serialized.contains("目录或检索工具"))
            assertFalse(serialized.contains("终端操作"))
        } finally { parent.cancel() }
    }

    @Test fun rootRebuildRetainsChildRoleAndDoesNotOverwriteTaskOrReintroducePrimaryGuidance() {
        var root=false;val requests=mutableListOf<ProviderRequest>()
        val result=AgentModelClient.complete(config.copy(deviceSensitiveReadTools=true),"TASK_SURVIVES_ROOT",AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("read result") },
            provider=provider { request -> requests+=request.copy(messages=JSONArray(request.messages.toString()),tools=JSONArray(request.tools.toString()))
                if(requests.size==1) {root=true;tool()} else answer()
            },capabilitiesProvider={ AgentToolCapabilities(rootAvailable=root) },
            toolFilter={ all -> JSONArray().also { kept -> (0 until all.length()).map(all::getJSONObject).filter { it.getJSONObject("function").getString("name") in setOf("terminal","read_file","get_logcat") }.forEach(kept::put) } },
            skillContext=skills,memoryContext=memory,runRole=AgentRunRole.SUBAGENT)
        assertEquals("done",result.content);assertEquals(2,requests.size)
        fun tools(request: ProviderRequest)=(0 until request.tools.length()).map {
            request.tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertEquals(setOf("terminal","read_file"),tools(requests.first()))
        assertEquals(setOf("terminal","read_file","get_logcat"),tools(requests.last()))
        for(request in requests) {
            assertTrue(request.messages.toString().contains("TASK_SURVIVES_ROOT"))
            val system=text(request.messages).takeWhile { it != "TASK_SURVIVES_ROOT" }
            val operational=system.single { it.contains("围绕当前委派目标") }
            assertFalse(operational.contains("observe_screen"))
        }
        val before=text(requests.first().messages)
        val after=text(requests.last().messages)
        assertFalse(before.single { it.contains("围绕当前委派目标") }.contains("Root 授权已确认"))
        assertTrue(after.single { it.contains("围绕当前委派目标") }.contains("Root 授权已确认"))
        assertEquals(before.single { it.contains("<memory_core>") },after.single { it.contains("<memory_core>") })
        assertEquals(before.single { it.contains("id=fixture") },after.single { it.contains("id=fixture") })
    }

    @Test fun ordinaryToolRoundKeepsTheChildSystemPrefixByteIdentical() {
        val requests=mutableListOf<ProviderRequest>()
        AgentModelClient.complete(config,"inspect",AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("result") },
            provider=provider { request -> requests+=request.copy(messages=JSONArray(request.messages.toString()));if(requests.size==1)tool() else answer() },
            runRole=AgentRunRole.SUBAGENT)
        fun prefix(r: ProviderRequest)=(0 until r.messages.length()).map(r.messages::getJSONObject).filter { it.getString("role")=="system" }.map { it.toString() }
        assertEquals(2,requests.size);assertEquals(prefix(requests.first()),prefix(requests.last()))
    }

    @Test fun mainConfigWithSubagentToolsDisabledStillUsesPrimaryUnlessExplicitlyMarked() {
        val messages=AgentPromptBuilder.buildSystemMessages(config.copy(subagentTools=false),SkillContext.EMPTY,AgentMemoryContext.DISABLED,false)
        assertTrue(messages.toString().contains("observe_screen"))
        assertFalse(messages.toString().contains("围绕当前委派目标"))
    }
}
