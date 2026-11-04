package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskManagerTest {
    private fun success(text: String = "verified evidence") = AgentModelClient.ToolResult(
        JSONObject().put("ok", true).put("summary", text).toString())
    private fun control(manager: SubagentTaskManager, action: String, task: SubagentTaskManager.Task? = null,
        text: String = "", revise: Boolean = false): JSONObject = JSONObject(manager.control(JSONObject()
        .put("action", action).put("task_id", task?.id.orEmpty()).put("text", text).put("revise", revise)
        .put("timeout_ms", 3000)).content)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

    @Test fun asynchronousStartAndUserSteeringWakeWaitWithoutCancellingChildren() {
        val parent = AgentRunController()
        SubagentTaskManager(parent).use { manager ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget()) {
                started.countDown(); release.await(3, TimeUnit.SECONDS); success()
            }
            await(started)
            assertNull(task.snapshot(true).opt("result"))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val waiting = pool.submit<JSONObject> { control(manager, "wait", task) }
                assertTrue(parent.steer("为什么慢？继续并保留原约束"))
                val result = waiting.get(3, TimeUnit.SECONDS)
                assertTrue(result.getBoolean("user_steering_pending"))
                assertFalse(task.controller.isCancelled)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun completeGuidanceIsDeliveredOnceAndAcknowledgedOnlyAfterResponse() {
        val parent = AgentRunController()
        SubagentTaskManager(parent).use { manager ->
            val start = CountDownLatch(1); val release = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget()) {
                start.countDown(); release.await(3, TimeUnit.SECONDS); success()
            }
            await(start)
            val text = "必须保留的约束、纠偏、路径和证据。".repeat(200)
            assertEquals("queued", control(manager, "guide", task, text, true).getString("delivery"))
            assertEquals("queued", task.snapshot().getJSONArray("guidance").getJSONObject(0).getString("delivery"))
            val injected = task.coordination.drainMessages()
            assertTrue(injected.single().contains(text))
            assertTrue(task.coordination.drainMessages().isEmpty())
            task.coordination.requestStarted()
            assertEquals("injected", task.snapshot().getJSONArray("guidance").getJSONObject(0).getString("delivery"))
            task.coordination.responseReceived(null)
            assertEquals("response_received", task.snapshot().getJSONArray("guidance").getJSONObject(0).getString("delivery"))
            assertEquals(2, task.snapshot().getInt("applied_revision"))
            release.countDown()
            control(manager, "wait", task)
            assertEquals("completed", task.snapshot().getString("status"))
        }
    }

    @Test fun resultForOldRevisionCannotCompleteNewGoal() {
        SubagentTaskManager(AgentRunController()).use { manager ->
            val release = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget()) {
                release.await(3, TimeUnit.SECONDS); success()
            }
            control(manager, "guide", task, "目标已经变更，不可用旧结果完成", true)
            release.countDown(); control(manager, "wait", task)
            assertEquals("completed_previous_revision", task.snapshot().getString("status"))
        }
    }

    @Test fun targetedCancelDoesNotCancelSiblingButParentCancelDoes() {
        val parent = AgentRunController()
        SubagentTaskManager(parent).use { manager ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            fun start(id: String) = manager.start(id, id, "work", SubagentTaskManager.Budget()) { task ->
                entered.countDown(); release.await(3, TimeUnit.SECONDS); task.controller.throwIfCancelled(); success()
            }
            val a = start("a"); val b = start("b"); await(entered)
            control(manager, "cancel", a)
            assertTrue(a.controller.isCancelled)
            assertFalse(b.controller.isCancelled)
            assertEquals("cancelling", a.snapshot().getString("status"))
            parent.cancel()
            assertTrue(b.controller.isCancelled)
            release.countDown()
        }
    }

    @Test fun pauseIsAcknowledgedAtSafeCheckpointAndCanResume() {
        SubagentTaskManager(AgentRunController()).use { manager ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget()) { task ->
                entered.countDown(); release.await(3, TimeUnit.SECONDS); task.coordination.checkpoint(); success()
            }
            await(entered)
            control(manager, "pause", task)
            assertEquals("pausing", task.snapshot().getString("status"))
            release.countDown(); control(manager, "wait", task)
            assertEquals("paused", task.snapshot().getString("status"))
            control(manager, "continue", task); control(manager, "wait", task)
            assertEquals("completed", task.snapshot().getString("status"))
        }
    }

    @Test fun decisionReportsWakeParentAndOrdinaryReportsAreNotRepeated() {
        SubagentTaskManager(AgentRunController()).use { manager ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget()) { task ->
                entered.countDown(); release.await(3, TimeUnit.SECONDS); task.coordination.checkpoint(); success()
            }
            await(entered)
            manager.report(task, JSONObject().put("text", "已查实事实与证据，下一步需决定").put("needs_decision", true))
            val report = manager.coordination.drainMessages().single()
            assertTrue(report.contains("需决定"))
            assertTrue(manager.coordination.drainMessages().isEmpty())
            release.countDown(); control(manager, "wait", task)
            assertEquals("waiting_decision", task.snapshot().getString("status"))
            assertFalse(manager.coordination.beforeFinish().isEmpty())
            control(manager, "cancel", task)
        }
    }

    @Test fun roundAndTokenSoftBudgetPausesUntilExplicitNewGrant() {
        SubagentTaskManager(AgentRunController()).use { manager ->
            val entered = CountDownLatch(1)
            val task = manager.start("child", "call", "work", SubagentTaskManager.Budget(rounds = 1, tokens = 10)) { task ->
                task.coordination.responseReceived(AgentTokenUsage(inputTokens = 11, outputTokens = 2))
                entered.countDown(); task.coordination.checkpoint(); success()
            }
            await(entered); control(manager, "wait", task)
            // The budget notification can wake before the checkpoint publishes its state.
            assertEquals("paused", task.snapshot().getString("status"))
            assertEquals(13, task.snapshot().getInt("observed_tokens"))
            assertEquals("budget", task.snapshot().getString("pause_reason"))
            control(manager, "continue", task); control(manager, "wait", task)
            assertEquals("completed", task.snapshot().getString("status"))
        }
    }

    @Test fun cancelledQueuedTaskRunsFinalizerWithoutStartingModelWork() {
        val parent = AgentRunController()
        SubagentTaskManager(parent).use { manager ->
            val entered = CountDownLatch(4)
            val release = CountDownLatch(1)
            repeat(4) { index -> manager.start("child-$index", "$index", "work", SubagentTaskManager.Budget()) {
                entered.countDown(); release.await(3, TimeUnit.SECONDS); success()
            } }
            await(entered)
            val finalized = CountDownLatch(1)
            var ran = false
            val queued = manager.start("queued", "q", "queued", SubagentTaskManager.Budget(), onFinish = { finalized.countDown() }) {
                ran = true; success()
            }
            control(manager, "cancel", queued)
            release.countDown(); await(finalized)
            assertFalse(ran)
            assertEquals("cancelled", queued.snapshot().getString("status"))
        }
    }

    @Test fun parentLoopPairsLaunchToolBeforeReceivingTerminalReport() {
        val parent = AgentRunController()
        SubagentTaskManager(parent).use { manager ->
            val release = CountDownLatch(1)
            var requestCount = 0
            val provider = object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    requestCount++
                    if (requestCount == 1) return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "launch")
                        .put("type", "function").put("function", JSONObject().put("name", "subagent").put("arguments", "{}")))))
                    if (requestCount == 2) {
                        assertEquals("tool", request.messages.getJSONObject(2).getString("role"))
                        assertTrue(request.messages.getJSONObject(2).getString("content").contains("started"))
                        release.countDown()
                    } else assertTrue(request.messages.toString().contains("verified evidence"))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "完成汇总").put("finish_reason", "stop"))
                }
            }
            val loop = AgentLoop(config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "开始")),
                tools = JSONArray().put(AgentSubagentToolCatalog.schema(emptyList())), provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor {
                    val task = manager.start("child", "launch", "work", SubagentTaskManager.Budget()) {
                        release.await(3, TimeUnit.SECONDS); success()
                    }
                    AgentModelClient.ToolResult(task.snapshot().put("ok", true).put("started", true).toString())
                }, runController = parent, traceFormatter = AgentTraceFormatter(), onEvent = {}, coordination = manager.coordination)
            assertEquals("完成汇总", loop.run().content)
            assertEquals(3, requestCount)
        }
    }
}
