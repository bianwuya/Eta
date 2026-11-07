package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCancellationBoundaryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "stable",
    )
    private fun answer() = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", "natural answer").put("finish_reason", "stop"))
    private fun provider(script: () -> ProviderResponse) = object : AgentProviderClient {
        override val id = "cancellation-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = script()
    }
    private fun assertCancelled(failure: Throwable?) {
        assertNotNull("cancel must terminate instead of succeeding", failure)
        assertTrue(failure is AgentModelExecutionException && failure.cause is AgentRunCancelledException)
    }

    @Test fun cancellationAfterPublishingAnswerWithoutChildrenCannotReturnSuccess() {
        val controller = AgentRunController()
        val finished = AtomicInteger()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "inspect", { error("no tools") }, provider = provider(::answer),
                runController = controller, onTranscript = { controller.cancel() },
                onEvent = { if (it is AgentEvent.RunFinished) finished.incrementAndGet() })
        }
        assertCancelled(failure)
        assertEquals(0, finished.get())
    }

    @Test fun cancellationInsideFinalEventCannotReturnASuccessResponse() {
        val controller = AgentRunController()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "inspect", { error("no tools") }, provider = provider(::answer),
                runController = controller, onEvent = { if (it is AgentEvent.RunFinished) controller.cancel() })
        }
        assertCancelled(failure)
    }

    @Test fun cancellationDuringFinalCompactionCannotReturnSuccess() = finalCompactionBoundary(cancel = true)

    @Test fun nonCancellationFinalCompactionFailureStillDeliversTheFullAnswer() = finalCompactionBoundary(cancel = false)

    private fun finalCompactionBoundary(cancel: Boolean) {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val summaries = AtomicInteger()
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "previous task $turn"),
            AgentModelClient.ConversationMessage("assistant", "verified fact $turn ".repeat(2200)),
        ) }
        val model = object : AgentProviderClient {
            override val id = "final-compaction-cancel-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries.incrementAndGet()
                    if (cancel) controller.cancel()
                    throw AgentModelFailure("FIXTURE_SUMMARY_FAILURE", false, "fixture summary failure")
                }
                onEvent(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 45_000)))
                return answer()
            }
        }
        fun complete() = AgentModelClient.complete(config.copy(contextWindow = 50_000), "inspect", { error("no tools") },
            provider = model, history = history, runController = controller, onEvent = events::add)
        if (cancel) {
            assertCancelled(assertThrows(AgentModelExecutionException::class.java) { complete() })
            assertEquals(0, events.count { it is AgentEvent.RunFinished })
        } else {
            val result = complete()
            assertEquals("natural answer", result.content)
            assertEquals("natural answer", result.contextSnapshot!!.messages.last().content)
            assertTrue(events.any { it is AgentEvent.ContextCompaction && it.reasonCode == "FIXTURE_SUMMARY_FAILURE" })
            assertEquals(1, events.count { it is AgentEvent.RunFinished })
        }
        assertEquals("fixture must actually enter final compaction", 1, summaries.get())
    }

    @Test fun completedModelCallDoesNotSealControllerForLaterCleanupOrReuse() {
        val controller = AgentRunController()
        val cleanup = AtomicInteger()
        val binding = controller.register { cleanup.incrementAndGet() }
        try {
            repeat(2) {
                val result = AgentModelClient.complete(config, "inspect", { error("no tools") },
                    provider = provider(::answer), runController = controller)
                assertEquals("natural answer", result.content)
                assertFalse(controller.isCancelled)
            }
            controller.cancel()
            assertTrue(controller.isCancelled)
            assertEquals(1, cleanup.get())
        } finally { binding.close(); controller.cancel() }
    }

    @Test fun cancelledChildCleanupBeforePendingCheckCannotReturnNaturalAnswerAsSuccess() = childCancellation(false)

    @Test fun cancellationAfterParentActuallyEntersWaitCannotPublishChildCompletion() = childCancellation(true)

    private fun childCancellation(cancelAfterWaiting: Boolean) {
        val controller = AgentRunController()
        val enteredCallback = CountDownLatch(1)
        val releaseParent = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val rounds = AtomicInteger()
        val finishes = AtomicInteger()
        val interruptions = AtomicInteger()
        val finishedEvents = AtomicInteger()
        val firstAnswer = AtomicBoolean()
        val result = AtomicReference<AgentModelClient.ModelResponse.Text?>()
        val failure = AtomicReference<Throwable?>()
        val owner = YieldableSubagents(controller, object : YieldableSubagents.Persistence {
            override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) = Unit
            override fun finished(taskId: String, report: String): Boolean { finishes.incrementAndGet(); return true }
            override fun interrupted() { interruptions.incrementAndGet() }
        }) { _, started ->
            started("fixture-child", "")
            check(releaseChild.await(5, TimeUnit.SECONDS))
            AgentModelClient.ToolResult("""{"ok":true,"status":"completed"}""")
        }
        val child = JSONObject().put("id", "launch").put("type", "function")
            .put("function", JSONObject().put("name", "subagent")
                .put("arguments", """{"prompt":"inspect","description":"inspect"}"""))
        val model = provider {
            when (rounds.incrementAndGet()) {
                1 -> ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(child)))
                2 -> answer()
                else -> error("cancelled run must not request another provider round")
            }
        }
        val runner = Thread {
            try {
                result.set(AgentModelClient.complete(config, "inspect", { error("unexpected parent tool") },
                    provider = model, runController = controller, yieldableSubagents = owner,
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()),
                    onEvent = { if (it is AgentEvent.RunFinished) finishedEvents.incrementAndGet() },
                    onTranscript = { transcript ->
                        if (transcript.any { it.content == "natural answer" } && firstAnswer.compareAndSet(false, true)) {
                            enteredCallback.countDown()
                            check(releaseParent.await(5, TimeUnit.SECONDS))
                        }
                    }))
            } catch (t: Throwable) { failure.set(t) }
        }
        try {
            runner.start()
            assertTrue(enteredCallback.await(3, TimeUnit.SECONDS))
            assertEquals(YieldableSubagents.Decision.WAIT, owner.decision())
            if (cancelAfterWaiting) {
                releaseParent.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!atChildWait(runner) && runner.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertTrue("parent must actually enter awaitWorkAvailable before cancel", atChildWait(runner))
                controller.cancel()
                releaseChild.countDown()
            } else {
                controller.cancel()
                releaseChild.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (owner.decision() != YieldableSubagents.Decision.FINISH && System.nanoTime() < deadline) Thread.yield()
                assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
                releaseParent.countDown()
            }
            runner.join(5000)
            assertFalse(runner.isAlive)
            assertCancelled(failure.get())
            assertNull(result.get())
            assertEquals(2, rounds.get())
            assertEquals(0, finishes.get())
            assertTrue(interruptions.get() > 0)
            assertEquals(0, finishedEvents.get())
            assertTrue(owner.peekReports().isEmpty())
        } finally {
            releaseChild.countDown(); releaseParent.countDown(); controller.cancel(); owner.close(); runner.join(5000)
        }
    }

    private fun atChildWait(worker: Thread): Boolean = worker.state == Thread.State.WAITING &&
        worker.stackTrace.any { it.className == AgentRunController::class.java.name && it.methodName == "awaitWorkAvailable" }
}
