package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YieldableSubagentResultTest {
    private class Persistence : YieldableSubagents.Persistence {
        val ids = Collections.synchronizedList(mutableListOf<String>())
        val outcomes = Collections.synchronizedList(mutableListOf<SubagentTaskResult>())
        val saved = java.util.concurrent.LinkedBlockingQueue<String>()
        override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) { ids += taskId }
        override fun finished(taskId: String, report: String): Boolean = error("typed result required")
        override fun finished(taskId: String, report: String, result: SubagentTaskResult): Boolean {
            outcomes += result
            saved.offer(report)
            return true
        }
        override fun interrupted() = Unit
    }

    private fun call(id: String) = AgentModelClient.ToolCall(id, "subagent", """{"prompt":"inspect","description":"inspect"}""")

    @Test fun everySemanticTerminalDeliversOnceAndOnlyCompletedSucceeds() {
        val controller = AgentRunController()
        val persistence = Persistence()
        val owner = YieldableSubagents(controller, persistence) { call, started ->
            started("same-child", "")
            val state = call.id
            val semantic = SubagentTaskResult.fromFinal("task_status: $state\nsummary: inspected\nevidence: Source:1\n" +
                "remaining: ${if (state in setOf("partial", "blocked")) "verify" else "-"}\nnext_action: -")
            AgentModelClient.ToolResult(semantic.attach(JSONObject().put("ok", true).put("status", "completed")
                .put("summary", "full-raw-$state")).toString())
        }
        try {
            SubagentTaskResult.Status.entries.forEach { status ->
                val accepted = JSONObject(owner.launch(call(status.wire)).content)
                assertNotNull(persistence.saved.poll(5, TimeUnit.SECONDS))
                controller.awaitWorkAvailable { owner.decision() != YieldableSubagents.Decision.WAIT }
                val report = owner.peekReports().single()
                assertEquals(accepted.getString("task_id"), report.taskId)
                assertEquals(status, report.taskStatus)
                assertEquals(status == SubagentTaskResult.Status.COMPLETED, report.succeeded)
                assertTrue(report.content.contains("full-raw-${status.wire}"))
                assertTrue(report.content.contains("不是终端用户发言或授权"))
                owner.consumed(listOf(report.taskId))
                assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
                owner.consumed(listOf(report.taskId))
                assertTrue(owner.peekReports().isEmpty())
            }
            assertEquals(5, persistence.ids.distinct().size)
            assertEquals(SubagentTaskResult.Status.entries, persistence.outcomes.map { it.status })
        } finally { owner.close(); controller.cancel() }
    }

    @Test fun legacyCompletionCannotSucceedOrHideItsFullReport() {
        val controller = AgentRunController()
        val persistence = Persistence()
        val owner = YieldableSubagents(controller, persistence) { _, started ->
            started("child", "")
            AgentModelClient.ToolResult("""{"ok":true,"status":"completed","summary":"blocked legacy full report"}""")
        }
        try {
            owner.launch(call("legacy"))
            assertNotNull(persistence.saved.poll(5, TimeUnit.SECONDS))
            controller.awaitWorkAvailable { owner.decision() != YieldableSubagents.Decision.WAIT }
            val report = owner.peekReports().single()
            assertFalse(report.succeeded)
            assertEquals(SubagentTaskResult.Status.UNKNOWN, report.taskStatus)
            assertTrue(report.content.contains("blocked legacy full report"))
        } finally { owner.close(); controller.cancel() }
    }
}
