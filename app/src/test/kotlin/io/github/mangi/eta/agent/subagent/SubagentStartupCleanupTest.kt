package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentStartupCleanupTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
    private val call = AgentModelClient.ToolCall("call", "subagent", """{"prompt":"任务","description":"检查"}""")

    private fun children(parent: AgentRunController): Int {
        val field = AgentRunController::class.java.getDeclaredField("children").apply { isAccessible = true }
        return (field.get(parent) as Set<*>).size
    }

    @Test fun progressFailureBeforeChildToolsStillClosesParentBinding() {
        val parent = AgentRunController()
        val executor = SubagentExecutor(conversationKey = "startup-progress", parentController = parent,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = { error("fixture progress error") })
        val result = executor.execute(1, call)
        assertTrue(result.content.contains("SUBAGENT_START_FAILED"))
        assertEquals(0, children(parent))
    }

    @Test fun startCallbackFailureClosesOwnedToolsAndParentBinding() {
        val parent = AgentRunController()
        val closed = AtomicBoolean(false)
        val owned = object : AgentModelClient.ToolExecutor, AutoCloseable {
            override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult = error("unexpected")
            override fun close() { closed.set(true) }
        }
        val executor = SubagentExecutor(conversationKey = "startup-callback", parentController = parent,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") }, childToolFactory = { _ -> owned },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        val result = executor.execute(1, call) { _, _ -> error("fixture callback error") }
        assertFalse(org.json.JSONObject(result.content).optBoolean("ok"))
        assertTrue(closed.get())
        assertEquals(0, children(parent))
    }
    @Test fun childFactoryUsesActualChildControllerAndAbortCancelsImageRequestWithoutCancellingParent() {
        val root = AgentRunController()
        val group = AgentRunController()
        val binding = root.registerChild(group)
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        io.github.mangi.eta.local.imagegen.ImageGenConfigStore.write(context,
            io.github.mangi.eta.local.imagegen.ImageGenConfig(io.github.mangi.eta.local.imagegen.ImageGenSource.CODEX_SUBSCRIPTION, "fixture", "fixture"))
        var actual: AgentRunController? = null
        var requestThread: Thread? = null
        lateinit var tool: AgentModelClient.ToolExecutor
        val executor = SubagentExecutor(conversationKey = "child-controller-image", parentController = group,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            childToolFactory = { child ->
                actual = child
                val image = io.github.mangi.eta.local.imagegen.ImageGenTool(context, { child }) { _, run, _ ->
                    assertSame(child, run)
                    val cancelBinding = run.register { cancelled.countDown() }
                    try {
                        started.countDown()
                        check(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        run.throwIfCancelled()
                        org.json.JSONObject().put("ok", true)
                    } finally { cancelBinding.close() }
                }
                AgentModelClient.ToolExecutor { image.execute(it) }.also { tool = it }
            },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        try {
            assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
                executor.execute(1, call) { _, _ ->
                    requestThread = Thread {
                        try { tool.execute(AgentModelClient.ToolCall("image", "generate_image", """{"prompt":"画树"}""")) }
                        catch (_: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { stopped.countDown() }
                    }.also(Thread::start)
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue(actual !== root && actual !== group)
                    group.cancel()
                    assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    actual!!.throwIfCancelled()
                }
            }
            assertFalse(root.isCancelled)
            assertTrue(actual!!.isCancelled)
        } finally {
            group.cancel(); requestThread?.join(5000); binding.close()
        }
    }

    @Test fun acceptedExecutorCancellationDuringParentStopRecoversInterruptedNotStartupFailure() {
        val root = AgentRunController()
        val group = AgentRunController()
        val groupBinding = root.registerChild(group)
        val cancellingChildren = java.util.concurrent.CountDownLatch(1)
        val releaseCancellation = java.util.concurrent.CountDownLatch(1)
        val workerReturned = java.util.concurrent.CountDownLatch(1)
        val childFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        // group.cancel() first cancels the actual child, then blocks here before root's
        // registered persistence.interrupted() callback can run.
        val pauseStop = group.register {
            cancellingChildren.countDown()
            check(releaseCancellation.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val run = "accepted-real-executor-cancel-gap"
        val store = io.github.mangi.eta.agent.runtime.AgentSubagentCheckpoint(context, run) { root.isCancelled }
        val executor = SubagentExecutor(conversationKey = run, parentController = group,
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        val owner = YieldableSubagents(root, store, cancelChildren = group::cancel) { childCall, started ->
            try {
                executor.execute(1, childCall) { id, snapshot ->
                    started(id, snapshot)
                    check(cancellingChildren.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    group.throwIfCancelled()
                }
            } catch (failure: Throwable) {
                childFailure.set(failure)
                throw failure
            } finally { workerReturned.countDown() }
        }
        val canceller = Thread { root.cancel() }
        try {
            val acknowledgement = org.json.JSONObject(owner.launch(call).content)
            assertEquals("accepted", acknowledgement.getString("status"))
            canceller.start()
            assertTrue(cancellingChildren.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(workerReturned.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(childFailure.get() is io.github.mangi.eta.agent.runtime.AgentRunCancelledException)
            assertTrue(root.isCancelled)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (owner.decision() != YieldableSubagents.Decision.FINISH && System.nanoTime() < deadline) Thread.yield()
            assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
            assertTrue(owner.peekReports().isEmpty())
            val recovered = io.github.mangi.eta.agent.runtime.AgentSubagentCheckpoint.recover(context, run, emptyList()).single()
            assertTrue(recovered.content.contains("interrupted"))
            assertFalse(recovered.content.contains("SUBAGENT_START_FAILED"))
        } finally {
            releaseCancellation.countDown(); canceller.join(5000); owner.close(); pauseStop.close(); groupBinding.close()
            io.github.mangi.eta.agent.runtime.AgentSubagentCheckpoint.remove(context, run)
        }
    }

    @Test fun startupCancellationIsNotConvertedToStartupFailure() {
        val parent = AgentRunController()
        val executor = SubagentExecutor(conversationKey = "startup-cancel", parentController = parent,
            configProvider = { throw io.github.mangi.eta.agent.runtime.AgentRunCancelledException() },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) { executor.execute(1, call) }
        assertEquals(0, children(parent))
    }

    @Test fun explicitlyCancelledChildCannotBeResumed() {
        val conversation = "cancelled-resume-test"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.EXPLORE)
        entry.explicitlyCancelled.set(true)
        SubagentSessionStore.releaseRunning(conversation, entry.subagentId)
        val parent = AgentRunController()
        val executor = SubagentExecutor(
            conversationKey = conversation,
            parentController = parent,
            configProvider = { config },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(),
            executionContext = null,
            onProgress = {},
        )
        val resumed = AgentModelClient.ToolCall(
            "resume-call", "subagent",
            org.json.JSONObject().put("resume", entry.subagentId)
                .put("prompt", "继续错误任务")
                .put("description", "意外恢复")
                .toString(),
        )
        val outcome = org.json.JSONObject(executor.execute(1, resumed).content)
        assertFalse(outcome.getBoolean("ok"))
        assertEquals("SUBAGENT_CANCELLED_FINAL", outcome.getString("code"))
        assertFalse(entry.running.get())
    }

    @Test fun startupJavaCancellationIsPreserved() {
        val parent = AgentRunController()
        val expected = java.util.concurrent.CancellationException("fixture cancellation")
        val executor = SubagentExecutor(conversationKey = "startup-java-cancel", parentController = parent,
            configProvider = { throw expected }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {})
        assertSame(expected, assertThrows(java.util.concurrent.CancellationException::class.java) { executor.execute(1, call) })
        assertEquals(0, children(parent))
    }

}
