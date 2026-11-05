package io.github.mangi.eta.local.subagentdisplay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentReadableTextIntegrationTest {
    @Test fun toolStartedTitleIsProjectedButDetailAndBackendStatsRemainIntact() {
        val tracker = SubagentExecutor.StepTracker("child", "coder", "test", "prompt", "model", false,
            contextWindow = 128_000)
        val command = "git status --short"
        assertTrue(tracker.onChildEvent(AgentEvent.ToolStarted(1, "call", "terminal", "终端 · 执行命令 · Linux", command)))
        val started = tracker.snapshot()
        assertEquals("查看 Git 状态", started.steps.single().title)
        assertEquals(command, started.steps.single().detail)
        assertEquals("查看 Git 状态", started.currentAction)
        assertEquals(128_000, started.contextWindow)
        assertEquals(0, started.compactionCount)
        assertEquals(SubagentActivitySnapshot.STATUS_RUNNING, started.status)
        tracker.onChildEvent(AgentEvent.ToolFinished(1, "call", "terminal", "完成摘要", 0, 0, true))
        val finished = tracker.snapshot().steps.single()
        assertEquals("查看 Git 状态", finished.title)
        assertEquals("$command\n完成摘要", finished.detail)
    }
}
