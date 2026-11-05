package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方同步子代理流程回归：工具目录只有同步 `subagent` / `subagent_profile`，子角色只回传最终
 * summary；实例在完成时释放执行占用并保留上下文供 `resume` 续接。
 */
class SubagentSyncFlowTest {
    @Test
    fun catalogExposesOnlySynchronousSubagentTools() {
        val tools = JSONArray()
        AgentSubagentToolCatalog.appendSchemas(tools, SubagentProfile.BUILTIN_PROFILES)
        val names = (0 until tools.length()).map { index ->
            tools.getJSONObject(index).getJSONObject("function").getString("name")
        }
        assertEquals(listOf(AgentSubagentToolCatalog.TOOL_NAME, AgentSubagentToolCatalog.PROFILE_TOOL_NAME), names)
        assertEquals(setOf("subagent", "subagent_profile"), AgentSubagentToolCatalog.names)
        assertFalse("subagent_control" in AgentSubagentToolCatalog.names)
        assertFalse("subagent_report" in AgentSubagentToolCatalog.names)

        val schema = AgentSubagentToolCatalog.schema(SubagentProfile.BUILTIN_PROFILES).toString()
        assertFalse(schema.contains("subagent_control"))
        assertFalse(schema.contains("subagent_report"))
        assertFalse(schema.contains("max_time_seconds"))
        assertFalse(schema.contains("max_rounds"))
        assertFalse(schema.contains("max_tokens"))
        assertFalse(schema.contains("budget"))
        assertTrue(schema.contains("accepted receipt"))
        assertTrue(schema.contains("not completion"))
    }

    @Test
    fun childRoleUsesFinalMessageProtocolWithoutControlTools() {
        val prompt = SubagentProfile.CODER.buildSystemPrompt()
        assertTrue(prompt.contains("只能看到你结束时的最后一条回复"))
        assertFalse(prompt.contains("subagent_report"))
        assertFalse(prompt.contains("needs_decision"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("subagent"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("subagent_profile"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("conversation_send"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("terminal_job"))
        assertTrue(SubagentProfile.CODER.isToolAllowed("terminal"))
    }

    @Test
    fun completedSubagentReleasesRunningAndKeepsHistoryForResume() {
        val profile = SubagentProfile.resolve(name = "sync-checker", catalog = SubagentProfile.BUILTIN_PROFILES)
        val entry = SubagentSessionStore.create("sync-flow-test", profile)
        assertTrue(entry.running.get())

        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "检查同步流程"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "同步完成"),
        )
        SubagentSessionStore.complete("sync-flow-test", entry.subagentId, history)

        val resumed = SubagentSessionStore.get("sync-flow-test", entry.subagentId)
        assertEquals(history, resumed?.history)
        assertFalse(resumed!!.running.get())
        assertTrue(resumed.running.compareAndSet(false, true))
        SubagentSessionStore.releaseRunning("sync-flow-test", entry.subagentId)
    }

    @Test
    fun completedSnapshotCarriesFinalSummaryAndBoundedPreview() {
        val tracker = SubagentExecutor.StepTracker("sub_1", "explore", "同步检查", "prompt", "model", false)
        tracker.markCompleted("最终 summary")
        val snapshot = tracker.snapshot()
        assertEquals(SubagentActivitySnapshot.STATUS_COMPLETED, snapshot.status)
        assertEquals("最终 summary", snapshot.output)
        assertTrue(tracker.snapshot().encodeBounded(12_000).length <= 12_000)
    }
}
