package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YieldableSubagentLoopTest {
    private class MemoryPersistence : YieldableSubagents.Persistence {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val finished = Collections.synchronizedList(mutableListOf<String>())
        override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) { started += taskId }
        override fun finished(taskId: String, report: String) { finished += taskId }
        override fun interrupted() = Unit
    }

    private fun childCall(id: String) = JSONObject().put("id", id).put("type", "function").put("function",
        JSONObject().put("name", "subagent").put("arguments", JSONObject().put("prompt", "任务-$id")
            .put("description", id).toString()))
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function").put("function",
        JSONObject().put("name", name).put("arguments", "{}"))
    private fun response(vararg calls: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls.toList())))
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))

    @Test fun acceptedWaitsWithoutPollingThenImageSteerAndFinalReportAtBoundary() {
        val controller = AgentRunController()
        val memory = MemoryPersistence()
        val release = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val afterSteer = CountDownLatch(1)
        val calls = AtomicInteger()
        val snapshots = Collections.synchronizedList(mutableListOf<String>())
        val toolSchemas = Collections.synchronizedList(mutableListOf<String>())
        val sessionIds = Collections.synchronizedList(mutableListOf<String>())
        val cacheKeys = Collections.synchronizedList(mutableListOf<String>())
        val image = AgentModelClient.ModelImage("data:image/png;base64,aW1hZ2U=", "image/png", 5)
        val owner = YieldableSubagents(controller, memory) { call, start ->
            start("sub-${call.id}", "")
            check(release.await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("summary", "完整最终报告").toString())
        }
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                snapshots += request.messages.toString()
                toolSchemas += request.tools.toString()
                sessionIds += request.sessionId
                cacheKeys += JSONObject().also { OpenAiPromptCache.apply(it, request) }.getString("prompt_cache_key")
                return when (calls.incrementAndGet()) {
                    1 -> response(childCall("c1"), call("ordinary", "get_current_context"))
                    2 -> { afterSteer.countDown(); answer("先处理用户的新方向") }
                    3 -> answer("汇总子代理结果")
                    else -> error("unexpected model polling")
                }
            }
        }
        var result: AgentModelClient.ModelResponse.Text? = null
        var failure: Throwable? = null
        val thread = Thread {
            try {
                result = AgentModelClient.complete(
                    config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid", apiKey = "fixture", model = "gpt-fixture", systemPrompt = "stable"),
                    prompt = "开始", toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{\"ok\":true}") },
                    provider = provider, runController = controller, sessionId = "stable-session",
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()), yieldableSubagents = owner,
                    onTranscript = { transcript -> if (transcript.any { it.role == "tool" && it.content.contains("accepted") }) accepted.countDown() },
                )
            } catch (t: Throwable) { failure = t }
        }
        try {
            thread.start()
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertTrue(controller.steer("改变方向", listOf(image)))
            assertTrue(afterSteer.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertFalse(controller.isCancelled)
            release.countDown()
            thread.join(5000)
            assertFalse(thread.isAlive)
            failure?.let { throw AssertionError(it) }
            assertEquals("汇总子代理结果", result!!.content)
            assertEquals(3, calls.get())
            assertEquals(1, memory.started.size)
            assertEquals(memory.started, memory.finished)
            val second = JSONArray(snapshots[1]); val third = JSONArray(snapshots[2])
            val roles = (0 until second.length()).map { second.getJSONObject(it).optString("role") }
            assertEquals(listOf("assistant", "tool", "tool", "user"), roles.takeLast(4))
            assertEquals("c1", second.getJSONObject(second.length()-3).getString("tool_call_id"))
            assertEquals("ordinary", second.getJSONObject(second.length()-2).getString("tool_call_id"))
            assertEquals(image.reference, second.getJSONObject(second.length()-1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertTrue(third.toString().contains("完整最终报告"))
            assertTrue(third.toString().contains("不是终端用户发言或授权"))
            for (index in 0 until second.length()) assertEquals(second.get(index).toString(), third.get(index).toString())
            assertEquals(1, toolSchemas.distinct().size)
            assertEquals(listOf("stable-session"), sessionIds.distinct())
            assertEquals(1, cacheKeys.distinct().size)
            assertEquals(1, result!!.transcript.count { it.messageId.startsWith("subagent-report-") })
        } finally { release.countDown(); controller.cancel(); owner.close(); thread.join(5000) }
    }

    @Test fun multipleChildrenCompleteOutOfOrderAndNaturalAnswerDoesNotTerminateEarly() {
        val controller = AgentRunController()
        val releases = mapOf("one" to CountDownLatch(1), "two" to CountDownLatch(1))
        val accepted = CountDownLatch(1)
        val secondRequest = CountDownLatch(1)
        val counter = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val owner = YieldableSubagents(controller, MemoryPersistence()) { call, start ->
            start("sub-${call.id}", "")
            check(releases.getValue(call.id).await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult("report-${call.id}")
        }
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests += request.messages.toString()
                return when (counter.incrementAndGet()) {
                    1 -> response(childCall("one"), childCall("two"))
                    2 -> { secondRequest.countDown(); answer("仍有一个任务") }
                    3 -> answer("全部结束")
                    else -> error("unexpected request")
                }
            }
        }
        var result: AgentModelClient.ModelResponse.Text? = null
        var failure: Throwable? = null
        val thread = Thread {
            try {
                result = AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                    "开始", AgentModelClient.ToolExecutor { error("unexpected") }, provider = provider, runController = controller,
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()), yieldableSubagents = owner,
                    onTranscript = { if (it.count { m -> m.role == "tool" && m.content.contains("accepted") } == 2) accepted.countDown() })
            } catch (t: Throwable) { failure = t }
        }
        try {
            thread.start(); assertTrue(accepted.await(5, TimeUnit.SECONDS)); assertEquals(1, counter.get())
            releases.getValue("two").countDown(); assertTrue(secondRequest.await(5, TimeUnit.SECONDS))
            assertTrue(thread.isAlive); assertTrue(requests[1].contains("report-two")); assertFalse(requests[1].contains("report-one"))
            releases.getValue("one").countDown(); thread.join(5000); assertFalse(thread.isAlive)
            failure?.let { throw AssertionError(it) }
            assertEquals("全部结束", result!!.content)
            assertEquals(2, result!!.transcript.count { it.messageId.startsWith("subagent-report-") })
            assertTrue(requests[2].contains("report-one"))
        } finally { releases.values.forEach { it.countDown() }; controller.cancel(); owner.close(); thread.join(5000) }
    }
    @Test fun failedLaunchIsARealFailureAndNeverEntersWaitingState() {
        val controller = AgentRunController()
        val memory = MemoryPersistence()
        val owner = YieldableSubagents(controller, memory) { _, _ ->
            AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"SUBAGENT_CONFIG_ERROR\"}")
        }
        try {
            val result = owner.launch(AgentModelClient.ToolCall("failed", "subagent", "{\"prompt\":\"x\",\"description\":\"x\"}"))
            assertTrue(result.content.contains("SUBAGENT_CONFIG_ERROR"))
            assertFalse(result.content.contains("accepted"))
            assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
            assertTrue(memory.started.isEmpty())
        } finally { owner.close() }
    }

    @Test fun steerCanDispatchAnotherChildAndDoIndependentWorkWithoutRerunningFirst() {
        val controller = AgentRunController()
        val firstAccepted = CountDownLatch(1)
        val secondAccepted = CountDownLatch(1)
        val afterSecond = CountDownLatch(1)
        val releases = mapOf("first" to CountDownLatch(1), "second" to CountDownLatch(1))
        val started = Collections.synchronizedList(mutableListOf<String>())
        val counter = AtomicInteger()
        val owner = YieldableSubagents(controller, MemoryPersistence()) { call, start ->
            started += call.id
            start("sub-${call.id}", "")
            check(releases.getValue(call.id).await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult("final-${call.id}")
        }
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                when (counter.incrementAndGet()) {
                    1 -> response(childCall("first"))
                    2 -> response(call("own-work", "get_current_context"), childCall("second"))
                    3 -> { afterSecond.countDown(); answer("先处理已完成的工作") }
                    4 -> answer("全部完成")
                    else -> error("unexpected")
                }
        }
        var failure: Throwable? = null
        val workCount = AtomicInteger()
        val thread = Thread {
            try {
                AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                    "开始", AgentModelClient.ToolExecutor { workCount.incrementAndGet(); AgentModelClient.ToolResult("{\"ok\":true}") },
                    provider = provider, runController = controller, additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()),
                    yieldableSubagents = owner, onTranscript = { transcript ->
                        val accepted = transcript.count { it.role == "tool" && it.content.contains("accepted") }
                        if (accepted >= 1) firstAccepted.countDown()
                        if (accepted >= 2) secondAccepted.countDown()
                    })
            } catch (t: Throwable) { failure = t }
        }
        try {
            thread.start(); assertTrue(firstAccepted.await(5, TimeUnit.SECONDS))
            assertTrue(controller.steer("再做一个并行任务，自己也工作"))
            assertTrue(secondAccepted.await(5, TimeUnit.SECONDS))
            assertEquals(1, workCount.get())
            assertEquals(listOf("first", "second"), started.toList())
            releases.getValue("second").countDown(); assertTrue(afterSecond.await(5, TimeUnit.SECONDS))
            releases.getValue("first").countDown(); thread.join(5000); assertFalse(thread.isAlive)
            failure?.let { throw AssertionError(it) }
            assertEquals(4, counter.get())
            assertEquals(listOf("first", "second"), started.toList())
        } finally { releases.values.forEach { it.countDown() }; controller.cancel(); owner.close(); thread.join(5000) }
    }

    @Test fun stopCancelsAcceptedChildAndUnblocksWaitingParent() {
        val parent = AgentRunController()
        val child = AgentRunController()
        val binding = parent.register(child::cancel)
        val ready = CountDownLatch(1)
        val childStopped = CountDownLatch(1)
        val owner = YieldableSubagents(parent, MemoryPersistence(), cancelChildren = child::cancel) { _, start ->
            val cancelled = child.register { childStopped.countDown() }
            try {
                start("sub-stop", "")
                childStopped.await(5, TimeUnit.SECONDS)
                child.throwIfCancelled()
                AgentModelClient.ToolResult("unexpected")
            } finally { cancelled.close() }
        }
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = response(childCall("stop"))
        }
        val thread = Thread {
            try {
                AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                    "开始", AgentModelClient.ToolExecutor { error("unexpected") }, provider = provider, runController = parent,
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()), yieldableSubagents = owner,
                    onTranscript = { if (it.any { m -> m.role == "tool" && m.content.contains("accepted") }) ready.countDown() })
            } catch (_: AgentModelExecutionException) { }
        }
        try {
            thread.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            parent.cancel(); assertTrue(childStopped.await(5, TimeUnit.SECONDS)); thread.join(5000)
            assertFalse(thread.isAlive); assertTrue(child.isCancelled)
        } finally { parent.cancel(); owner.close(); binding.close(); thread.join(5000) }
    }

    @Test fun ordinaryResultsAreCheckpointedIndividuallyWithoutSendingAnIncompleteBatch() {
        val controller = AgentRunController()
        val owner = YieldableSubagents(controller, MemoryPersistence()) { _, _ -> error("no child expected") }
        val firstSaved = CountDownLatch(1); val release = CountDownLatch(1); val rounds = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                when (rounds.incrementAndGet()) {
                    1 -> response(call("one", "get_current_context"), call("two", "get_current_context"))
                    2 -> {
                        val tools = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                            .filter { it.optString("role") == "tool" }
                        assertEquals(listOf("one", "two"), tools.map { it.getString("tool_call_id") })
                        answer("done")
                    }
                    else -> error("unexpected request")
                }
        }
        var failure: Throwable? = null
        val thread = Thread {
            try {
                AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                    "开始", AgentModelClient.ToolExecutor { call ->
                        if (call.id == "two") check(release.await(5, TimeUnit.SECONDS))
                        AgentModelClient.ToolResult("result-${call.id}")
                    }, provider = provider, runController = controller, yieldableSubagents = owner,
                    onTranscript = { transcript -> if (transcript.any { it.role == "tool" && it.toolCallId == "one" && it.content == "result-one" }) firstSaved.countDown() })
            } catch (t: Throwable) { failure = t }
        }
        try {
            thread.start(); assertTrue(firstSaved.await(5, TimeUnit.SECONDS)); assertEquals(1, rounds.get())
            release.countDown(); thread.join(5000); assertFalse(thread.isAlive)
            failure?.let { throw AssertionError(it) }; assertEquals(2, rounds.get())
        } finally { release.countDown(); controller.cancel(); owner.close(); thread.join(5000) }
    }

}
