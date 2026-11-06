package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanSubagentResultTest {
    private fun checkResult(envelope: JSONObject, success: Boolean) {
        val directory = Files.createTempDirectory("plan-result-").toFile()
        val controller = AgentRunController()
        val run = "plan-semantic-result"
        try {
            PlanModeStore.resetForTest()
            PlanModeStore.initializeAt(directory)
            PlanModeStore.listen(run, controller::signalWorkAvailable) { !controller.isCancelled }
            val args = JSONObject().put("prompt", "inspect").put("description", "inspect")
            val gate = PlanRunGate("chat", run, controller)
            val plan = PlanModeStore.propose("chat", run, "Test", "Inspect then act", listOf(
                PlanStep("child", "Inspect", actions = listOf(PlanAction("subagent", PlanRunGate.canonical(args)))),
                PlanStep("next", "Dependent", dependsOn = listOf("child"), actions = listOf(
                    PlanAction("terminal", "{\"command\":\"fixture\"}")))))
            assertTrue(PlanModeStore.approve(plan.planId, plan.revision))
            val begin = AgentModelClient.ToolCall(round = 1, id = "begin", name = PlanRunGate.BEGIN,
                argumentsJson = """{"step_id":"child","revision":1}""")
            assertTrue(JSONObject(gate.execute(begin) { error("internal") }.content).getBoolean("ok"))
            gate.execute(AgentModelClient.ToolCall(round = 2, id = "child", name = "subagent", argumentsJson = args.toString())) {
                AgentModelClient.ToolResult(envelope.toString())
            }
            assertEquals(if (success) "completed" else "blocked", PlanModeStore.current(run)!!.steps.first().status)
            val next = gate.execute(AgentModelClient.ToolCall(round = 3, id = "next", name = PlanRunGate.BEGIN,
                argumentsJson = """{"step_id":"next","revision":1}""")) { error("internal") }
            assertEquals(success, JSONObject(next.content).getBoolean("ok"))
        } finally {
            controller.cancel()
            PlanModeStore.resetForTest()
            directory.deleteRecursively()
        }
    }

    @Test fun synchronousLifecycleOnlyDoesNotCompleteTask() =
        checkResult(JSONObject().put("ok", true).put("status", "completed").put("summary", "legacy success"), false)

    @Test fun synchronousPartialBlockedFailedAndUnknownDoNotReleaseDependency() {
        listOf("partial", "blocked", "failed", "unknown").forEach { status ->
            val semantic = SubagentTaskResult.fromFinal(
                "task_status: $status\nsummary: inspected\nevidence: Source.kt:1\nremaining: device verification\nnext_action: verify")
            checkResult(semantic.attach(JSONObject().put("ok", true).put("status", "completed")), false)
        }
    }

    @Test fun synchronousExplicitCompletionReleasesDependency() {
        val semantic = SubagentTaskResult.fromFinal(
            "task_status: completed\nsummary: inspected\nevidence: Source.kt:1\nremaining: -\nnext_action: -")
        checkResult(semantic.attach(JSONObject().put("ok", true).put("status", "completed")), true)
    }
}
