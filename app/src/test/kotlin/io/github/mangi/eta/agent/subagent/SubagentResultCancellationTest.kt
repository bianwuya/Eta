package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentResultCancellationTest {
    private fun cancel() = AgentModelClient.ToolCall("cancel", "subagent_control",
        """{"action":"cancel","subagent_id":"child"}""")

    @Test fun acceptedTargetedCancelRejectsLateSuccessfulResult() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val binding = registry.register("child", "test", "call", child)
        try {
            assertTrue(JSONObject(registry.execute(cancel()).content).getBoolean("ok"))
            assertThrows(AgentRunCancelledException::class.java) {
                registry.finish("child", "call", AgentModelClient.ToolResult("success"))
            }
        } finally { binding.close() }
    }

    @Test fun finalizedResultRejectsLateCancelInsteadOfAcknowledgingIt() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val binding = registry.register("child", "test", "call", child)
        try {
            val result = AgentModelClient.ToolResult("success")
            assertSame(result, registry.finish("child", "call", result))
            assertEquals("SUBAGENT_NOT_RUNNING", JSONObject(registry.execute(cancel()).content).getString("code"))
            assertFalse(child.isCancelled)
            assertThrows(IllegalStateException::class.java) { registry.finish("child", "call", result) }
        } finally { binding.close() }
    }

    @Test fun wrongInvocationCannotFinalizeAnotherChild() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val binding = registry.register("child", "test", "call", child)
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.finish("child", "other-call", AgentModelClient.ToolResult("success"))
            }
            assertTrue(JSONObject(registry.execute(cancel()).content).getBoolean("ok"))
        } finally { binding.close() }
    }

    @Test fun cancellationCallbacksCanWaitForResultWithoutHoldingItsLock() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val workerDone = java.util.concurrent.CountDownLatch(1)
        var worker: Thread? = null
        val cancellation = child.register {
            worker = Thread {
                try {
                    assertThrows(AgentRunCancelledException::class.java) {
                        registry.finish("child", "call", AgentModelClient.ToolResult("late-success"))
                    }
                } finally { workerDone.countDown() }
            }.also(Thread::start)
            assertTrue("cancel callback must not deadlock on result lock", workerDone.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        val binding = registry.register("child", "test", "call", child)
        try {
            assertTrue(JSONObject(registry.execute(cancel()).content).getBoolean("ok"))
            assertTrue(workerDone.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { worker?.join(3000); cancellation.close(); binding.close() }
    }
}
