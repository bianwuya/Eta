package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentLiveControlTest {
    private fun control(action: String, id: String? = null, instruction: String? = null): AgentModelClient.ToolCall =
        AgentModelClient.ToolCall(
            id = "ctrl-$action",
            name = AgentSubagentToolCatalog.CONTROL_TOOL_NAME,
            argumentsJson = JSONObject().put("action", action)
                .apply { id?.let { put("subagent_id", it) } }
                .apply { instruction?.let { put("instruction", it) } }
                .toString(),
        )

    private fun result(registry: SubagentControlRegistry, call: AgentModelClient.ToolCall): JSONObject =
        JSONObject(registry.execute(call).content)

    @Test
    fun listPauseReviseAndResumeTargetOnlyOneChild() {
        val parent = AgentRunController()
        val first = AgentRunController()
        val second = AgentRunController()
        val registry = SubagentControlRegistry()
        val firstLink = parent.registerChild(first)
        val secondLink = parent.registerChild(second)
        val firstBinding = registry.register("sub_1", "查找文件", "call-1", first)
        val secondBinding = registry.register("sub_2", "核对日志", "call-2", second)
        try {
            val list = result(registry, control("list"))
            assertTrue(list.getBoolean("ok"))
            assertEquals(2, list.getInt("count"))
            assertEquals("sub_1", list.getJSONArray("active").getJSONObject(0).getString("subagent_id"))

            val paused = result(registry, control("pause", "sub_1"))
            assertEquals("pause_requested", paused.getString("status"))
            assertTrue(first.isPaused)
            assertFalse(second.isPaused)
            assertEquals("pause_requested", result(registry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("status"))

            val revised = result(registry, control("revise", "sub_1", "停止旧的目录搜索，改成只读检查"))
            assertEquals("revision_queued", revised.getString("status"))
            assertFalse(first.isPaused)
            assertTrue(first.hasPendingParentDirective)
            assertEquals("revision_pending", result(registry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("status"))
            val input = first.pollSteeringInput()
            assertNotNull(input)
            assertEquals(AgentRunController.SteeringOrigin.PARENT_AGENT, input!!.origin)
            assertEquals("停止旧的目录搜索，改成只读检查", input.text)
            assertNull(second.pollSteeringInput())

            assertEquals("pause_requested", result(registry, control("pause", "sub_1")).getString("status"))
            assertEquals("resume_requested", result(registry, control("resume", "sub_1")).getString("status"))
            assertFalse(first.isPaused)
            assertFalse(parent.isCancelled)
        } finally {
            firstBinding.close()
            secondBinding.close()
            firstLink.close()
            secondLink.close()
            parent.cancel()
        }
    }

    @Test
    fun cancelIsTargetedAndCannotCrossParentRun() {
        val oneRegistry = SubagentControlRegistry()
        val anotherRegistry = SubagentControlRegistry()
        val first = AgentRunController()
        val second = AgentRunController()
        val firstLink = oneRegistry.register("sub_1", "first run", "call-a", first)
        val secondLink = anotherRegistry.register("sub_1", "another run", "call-b", second)
        try {
            assertEquals("cancel_requested", result(oneRegistry, control("cancel", "sub_1")).getString("status"))
            assertTrue(first.isCancelled)
            assertFalse(second.isCancelled)
            assertEquals("cancelling", result(oneRegistry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("status"))
            assertEquals("SUBAGENT_ALREADY_CANCELLING",
                result(oneRegistry, control("revise", "sub_1", "new task")).getString("code"))
            assertEquals("running", result(anotherRegistry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("status"))
        } finally {
            firstLink.close()
            secondLink.close()
            first.cancel()
            second.cancel()
        }
        assertEquals(0, result(oneRegistry, control("list")).getInt("count"))
        assertEquals("SUBAGENT_NOT_RUNNING", result(oneRegistry, control("cancel", "sub_1")).getString("code"))
    }

    @Test
    fun pauseStateIsConfirmedOnlyAtExecutionCheckpoint() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val link = registry.register("sub_pause", "pause status", "call-p", child)
        val started = CountDownLatch(1)
        val worker = thread {
            started.countDown()
            child.throwIfCancelled()
        }
        try {
            // First resume the eagerly started worker, then create a fresh blocked checkpoint.
            worker.join(1_000)
            assertFalse(worker.isAlive)
            assertEquals("pause_requested", result(registry, control("pause", "sub_pause")).getString("status"))
            assertEquals("pause_requested", result(registry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("status"))

            val waiting = thread { child.throwIfCancelled() }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!child.isPausedAtCheckpoint && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                assertTrue(child.isPausedAtCheckpoint)
                assertEquals("paused", result(registry, control("list"))
                    .getJSONArray("active").getJSONObject(0).getString("status"))
                assertEquals("resume_requested", result(registry, control("resume", "sub_pause")).getString("status"))
                waiting.join(1_000)
                assertFalse(waiting.isAlive)
            } finally {
                child.resume()
                waiting.join(1_000)
            }
        } finally {
            child.cancel()
            link.close()
            worker.join(1_000)
        }
    }

    @Test
    fun sealedChildDoesNotAcknowledgeUndeliverableRevision() {
        val registry = SubagentControlRegistry()
        val child = AgentRunController()
        val link = registry.register("sub_end", "already finished", "call-end", child)
        try {
            assertNull(child.pollSteeringInputOrSeal())
            assertEquals("SUBAGENT_NOT_ACCEPTING",
                result(registry, control("revise", "sub_end", "change direction")).getString("code"))
            assertFalse(child.hasPendingParentDirective)
            assertEquals("INVALID_ARGUMENTS", result(registry, control("revise", "sub_end", "")).getString("code"))
            assertEquals("UNKNOWN_ACTION", result(registry, control("restart", "sub_end")).getString("code"))
        } finally {
            link.close()
            child.cancel()
        }
    }

    @Test
    fun concurrentCancelAndCompletionNeverChangesAnotherChild() {
        val registry = SubagentControlRegistry()
        val first = AgentRunController()
        val unaffected = AgentRunController()
        val link = registry.register("sub_1", "race", "call-race", first)
        val other = registry.register("sub_2", "unaffected", "call-other", unaffected)
        val ready = CountDownLatch(1)
        val outcomeRef = AtomicReference<JSONObject>()
        val worker = thread {
            ready.await()
            outcomeRef.set(result(registry, control("cancel", "sub_1")))
        }
        try {
            ready.countDown()
            link.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            val outcome = outcomeRef.get()
            assertNotNull(outcome)
            assertTrue(outcome!!.optString("status") == "cancel_requested" ||
                outcome.optString("code") == "SUBAGENT_NOT_RUNNING")
            assertFalse(unaffected.isCancelled)
            assertEquals("sub_2", result(registry, control("list"))
                .getJSONArray("active").getJSONObject(0).getString("subagent_id"))
        } finally {
            first.cancel()
            unaffected.cancel()
            link.close()
            other.close()
            worker.join(2_000)
        }
    }
}
