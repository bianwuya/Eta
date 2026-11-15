package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentModelRetry
import io.github.mangi.eta.agent.model.AgentContextSession
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentActivityStatsTest {
    private fun tracker(
        counter: AtomicInteger = AtomicInteger(0),
        resumed: Boolean = false,
        window: Int? = 128_000,
        entry: SubagentSessionStore.Entry? = null,
        model: String = "model-a",
        provider: String = "provider-a",
        now: () -> Long = { 1_700_000_000_000L },
    ) = SubagentExecutor.StepTracker("child", "coder", "test", "prompt", "Readable Model", resumed,
        contextWindow = window, successfulCompactions = counter,
        previousContext = if (resumed) entry?.lastContext else null,
        modelId = model, providerId = provider, onContextChanged = { entry?.lastContext = it }, now = now)

    private fun usage(value: Int?) = AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = value))
    private fun compaction(id: String, phase: String, after: Int? = null) =
        AgentEvent.ContextCompaction(id, phase, tokensBefore = 90_000, tokensAfter = after)

    @Test
    fun usageAndOnlySuccessfulCompactionAreReportedWithoutStaleTokens() {
        val tracker = tracker()
        assertNull(tracker.snapshot().contextTokens)
        assertEquals(128_000, tracker.snapshot().contextWindow)
        assertEquals(0, tracker.snapshot().compactionCount)

        assertTrue(tracker.onChildEvent(usage(90_000)))
        assertEquals(90_000, tracker.snapshot().contextTokens)
        assertFalse(tracker.onChildEvent(compaction("one", AgentEvent.ContextCompaction.PHASE_STARTED)))
        assertFalse(tracker.onChildEvent(compaction("one", "failed")))
        assertEquals(0, tracker.snapshot().compactionCount)
        assertEquals(90_000, tracker.snapshot().contextTokens)

        val completed = compaction("two", AgentEvent.ContextCompaction.PHASE_COMPLETED)
        assertTrue(tracker.onChildEvent(completed))
        assertEquals(1, tracker.snapshot().compactionCount)
        assertNull(tracker.snapshot().contextTokens) // Current runtime does not supply tokensAfter.
        assertEquals(90_000, tracker.snapshot().contextReading?.tokens)
        assertEquals(true, tracker.snapshot().contextReading?.beforeCompaction)
        assertFalse(tracker.onChildEvent(completed)) // A repeated completion is not another operation.
        assertEquals(1, tracker.snapshot().compactionCount)

        tracker.onChildEvent(usage(12_345))
        assertEquals(12_345, tracker.snapshot().contextTokens)
        tracker.onChildEvent(compaction("three", AgentEvent.ContextCompaction.PHASE_COMPLETED, 7_654))
        assertEquals(2, tracker.snapshot().compactionCount)
        assertEquals(7_654, tracker.snapshot().contextTokens)
        assertEquals(SubagentContextReading.SOURCE_COMPACTION, tracker.snapshot().contextReading?.source)
        assertFalse(tracker.onChildEvent(usage(null)))
        assertEquals(7_654, tracker.snapshot().contextTokens)
        assertFalse(tracker.onChildEvent(usage(-1)))
        assertEquals(7_654, tracker.snapshot().contextTokens)
    }

    @Test
    fun retryAndResumeDoNotPresentOldUsageAsNewContext() {
        val conversation = "activity-stats-resume-test"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER)
        val first = tracker(entry.compactionCount, entry = entry)
        first.onChildEvent(usage(50_000))
        first.onChildEvent(compaction("first", AgentEvent.ContextCompaction.PHASE_COMPLETED, 9_000))
        first.onChildEvent(usage(15_000))
        first.onChildEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "STREAM_INCOMPLETE"))
        assertEquals(15_000, first.snapshot().contextTokens)
        assertEquals(SubagentContextReading.ATTEMPT_FAILED, first.snapshot().contextReading?.attempt)
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "done"))
        SubagentSessionStore.complete(conversation, entry.subagentId, history)

        val saved = requireNotNull(SubagentSessionStore.get(conversation, entry.subagentId))
        assertEquals(history, saved.history)
        assertTrue(saved.running.compareAndSet(false, true))
        val resumed = tracker(saved.compactionCount, resumed = true, window = 64_000, entry = saved, model = "model-b")
        assertEquals(1, resumed.snapshot().compactionCount)
        assertEquals(64_000, resumed.snapshot().contextWindow)
        assertNull(resumed.snapshot().contextTokens)
        assertEquals(15_000, resumed.snapshot().contextReading?.tokens)
        assertEquals(true, resumed.snapshot().contextReading?.previousInvocation)
        assertEquals(true, resumed.snapshot().contextReading?.modelChanged)
        assertEquals(SubagentContextReading.ATTEMPT_FAILED, resumed.snapshot().contextReading?.attempt)
        resumed.onChildEvent(compaction("second", AgentEvent.ContextCompaction.PHASE_COMPLETED, 4_000))
        assertEquals(2, resumed.snapshot().compactionCount)
        assertEquals(1, first.snapshot().compactionCount) // Prior invocation remains a snapshot.
        SubagentSessionStore.releaseRunning(conversation, entry.subagentId)
        assertEquals(2, SubagentSessionStore.get(conversation, entry.subagentId)?.compactionCount?.get())
        assertEquals(0, tracker().snapshot().compactionCount) // Another child is independent.
    }

    @Test
    fun validReportSurvivesActual502503RetryChainAndNullUsage() {
        val tracker = tracker()
        tracker.onChildEvent(AgentEvent.RoundStarted(1, 2))
        tracker.onChildEvent(usage(87_400))
        tracker.onChildEvent(AgentEvent.AssistantReceived(1, 1, "", emptyList()))
        var calls = 0
        val retries = mutableListOf<String>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                calls++
                // Accurate input alone must never become contextTokens or replace the report.
                onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 87_311, contextTokens = null)))
                throw AgentModelFailure.http(if (calls == 1) 502 else 503, "")
            }
        }
        val failure = runCatching {
            AgentModelRetry { _, _ -> }.complete(
                initialRound = 2,
                request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid",
                    apiKey = "fixture", model = "model-a", systemPrompt = ""), JSONArray(), JSONArray()),
                provider = provider,
                controller = AgentRunController(),
                onEvent = { event ->
                    if (event is AgentEvent.ModelRetryScheduled) retries += event.reasonCode
                    tracker.onChildEvent(event)
                },
                onProviderEvent = { round, event ->
                    if (event is ProviderEvent.Usage) tracker.onChildEvent(AgentEvent.UsageReceived(round, event.usage))
                },
                discardAttemptReasoning = {},
            )
        }.exceptionOrNull()
        assertTrue(failure is AgentModelFailure)
        assertEquals(3, calls)
        assertEquals(listOf("HTTP_502", "HTTP_503"), retries)
        tracker.markFailed("HTTP 503", "")
        val snapshot = tracker.snapshot()
        assertEquals(87_400, snapshot.contextTokens)
        assertEquals(87_400, snapshot.contextReading?.tokens)
        assertEquals(SubagentContextReading.ATTEMPT_COMPLETED, snapshot.contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_FAILED, snapshot.contextRequestState)
    }

    @Test
    fun failedAttemptReportAndRetryRecoveryAreLabeledSeparately() {
        var clock = 100L
        val tracker = tracker(now = { clock++ })
        tracker.onChildEvent(AgentEvent.RoundStarted(1, 1))
        tracker.onChildEvent(usage(20_000))
        tracker.onChildEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "HTTP_502"))
        assertEquals(SubagentContextReading.ATTEMPT_FAILED, tracker.snapshot().contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_RETRYING, tracker.snapshot().contextRequestState)
        tracker.onChildEvent(AgentEvent.RoundStarted(2, 1))
        tracker.onChildEvent(usage(null))
        tracker.onChildEvent(usage(21_000))
        assertEquals(101L, tracker.snapshot().contextReading?.at)
        assertEquals(SubagentContextReading.ATTEMPT_REPORTED, tracker.snapshot().contextReading?.attempt)
        tracker.onChildEvent(AgentEvent.AssistantReceived(2, 3, "", emptyList()))
        assertEquals(SubagentContextReading.ATTEMPT_COMPLETED, tracker.snapshot().contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_COMPLETED, tracker.snapshot().contextRequestState)
        tracker.onChildEvent(AgentEvent.RoundStarted(3, 1))
        tracker.onChildEvent(usage(22_000))
        tracker.markFailed("last attempt failed", "")
        assertEquals(22_000, tracker.snapshot().contextReading?.tokens)
        assertEquals(SubagentContextReading.ATTEMPT_FAILED, tracker.snapshot().contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_FAILED, tracker.snapshot().contextRequestState)
    }

    @Test
    fun inputOnlyWithoutAnyContextStaysUnknownAndCancellationIsNotFailure() {
        val tracker = tracker()
        tracker.onChildEvent(AgentEvent.RoundStarted(1, 1))
        assertFalse(tracker.onChildEvent(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 87_311))))
        assertNull(tracker.snapshot().contextTokens)
        assertNull(tracker.snapshot().contextReading)
        tracker.onChildEvent(usage(0))
        tracker.markCancelled("")
        assertEquals(0, tracker.snapshot().contextTokens)
        assertEquals(SubagentContextReading.ATTEMPT_CANCELLED, tracker.snapshot().contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_CANCELLED, tracker.snapshot().contextRequestState)
    }

    @Test
    fun resumeAndRepeatedResumeKeepOriginBeforeCompactionUntilNewMeasurement() {
        val entry = SubagentSessionStore.create("stats-multi-resume", SubagentProfile.CODER)
        val first = tracker(entry.compactionCount, entry = entry)
        first.onChildEvent(AgentEvent.RoundStarted(1, 1))
        first.onChildEvent(usage(31_000))
        first.onChildEvent(AgentEvent.AssistantReceived(1, 1, "", emptyList()))
        first.onChildEvent(compaction("compact", AgentEvent.ContextCompaction.PHASE_COMPLETED))
        val second = tracker(entry.compactionCount, resumed = true, entry = entry, model = "model-b")
        assertNull(second.snapshot().contextTokens)
        assertEquals(31_000, second.snapshot().contextReading?.tokens)
        assertEquals(true, second.snapshot().contextReading?.previousInvocation)
        assertEquals(true, second.snapshot().contextReading?.beforeCompaction)
        assertEquals(true, second.snapshot().contextReading?.modelChanged)
        second.onChildEvent(AgentEvent.RoundStarted(1, 1))
        second.onChildEvent(usage(null))
        second.markFailed("HTTP 503", "")
        val third = tracker(entry.compactionCount, resumed = true, entry = entry, model = "model-b")
        assertEquals(true, third.snapshot().contextReading?.modelChanged) // Still compared with original reporter.
        third.onChildEvent(AgentEvent.RoundStarted(1, 1))
        third.onChildEvent(usage(3_000))
        assertEquals(3_000, third.snapshot().contextTokens)
        assertEquals(false, third.snapshot().contextReading?.previousInvocation)
        assertEquals(false, third.snapshot().contextReading?.beforeCompaction)
        SubagentSessionStore.releaseRunning("stats-multi-resume", entry.subagentId)
    }

    @Test
    fun realExecutorResumeCarriesHistoricalReportUntilNewUsageArrives() {
        val conversation = "activity-stats-real-resume"
        var requests = 0
        val progress = mutableListOf<SubagentActivitySnapshot>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests++
                if (requests == 1) onEvent(ProviderEvent.Usage(AgentTokenUsage(contextTokens = 24_000)))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        val executor = SubagentExecutor(conversationKey = conversation, parentController = AgentRunController(),
            configProvider = { AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
                model = "model-a", providerId = "provider-a", systemPrompt = "system", autoCompactionEnabled = false) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected tool") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null,
            onProgress = { SubagentActivitySnapshot.decode(it.snapshotJson)?.let(progress::add) },
            providerFactory = { provider })
        val first = executor.execute(1, AgentModelClient.ToolCall("first", "subagent", JSONObject()
            .put("subagent_type", "coder").put("prompt", "first").put("description", "first").toString()))
        val child = JSONObject(first.content).getString("subagent_id")
        val firstSnapshot = requireNotNull(SubagentActivitySnapshot.decode(first.command))
        assertEquals(24_000, firstSnapshot.contextTokens)
        assertEquals(SubagentContextReading.ATTEMPT_COMPLETED, firstSnapshot.contextReading?.attempt)
        assertEquals(SubagentContextReading.ATTEMPT_COMPLETED,
            SubagentSessionStore.get(conversation, child)?.lastContext?.reading?.attempt)
        progress.clear()
        val second = executor.execute(2, AgentModelClient.ToolCall("second", "subagent", JSONObject()
            .put("resume", child).put("prompt", "continue").put("description", "resume").toString()))
        val snapshot = requireNotNull(SubagentActivitySnapshot.decode(second.command))
        assertEquals(2, requests)
        assertTrue(progress.any { it.resumed && it.contextReading?.tokens == 24_000 })
        assertNull(snapshot.contextTokens)
        assertEquals(24_000, snapshot.contextReading?.tokens)
        assertEquals(true, snapshot.contextReading?.previousInvocation)
        assertEquals(false, snapshot.contextReading?.modelChanged)
        assertEquals(firstSnapshot.contextReading?.at, snapshot.contextReading?.at)
        assertEquals(SubagentContextReading.ATTEMPT_COMPLETED, snapshot.contextReading?.attempt)
        assertEquals(SubagentActivitySnapshot.REQUEST_COMPLETED, snapshot.contextRequestState)
    }

    @Test
    fun configFailureOnRealResumePreservesTheKnownCount() {
        val conversation = "activity-stats-failed-config-test"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER)
        entry.compactionCount.set(2)
        entry.lastContext = SubagentSessionStore.ContextState(
            SubagentContextReading(22_000, at = 1234), "model-a", "provider-a",
        )
        SubagentSessionStore.releaseRunning(conversation, entry.subagentId)
        val executor = SubagentExecutor(
            conversationKey = conversation,
            parentController = AgentRunController(),
            configProvider = { error("fixture config error") },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected tool") },
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, onProgress = {},
        )
        val call = AgentModelClient.ToolCall("resume-call", "subagent", JSONObject()
            .put("resume", entry.subagentId).put("prompt", "continue").put("description", "test").toString())
        val result = executor.execute(1, call)
        assertEquals("SUBAGENT_CONFIG_ERROR", JSONObject(result.content).getString("code"))
        val snapshot = requireNotNull(SubagentActivitySnapshot.decode(result.command))
        assertEquals(2, snapshot.compactionCount)
        assertNull(snapshot.contextTokens)
        assertNull(snapshot.contextWindow)
        assertEquals(22_000, snapshot.contextReading?.tokens)
        assertEquals(true, snapshot.contextReading?.previousInvocation)
        assertNull(snapshot.contextReading?.modelChanged)
        assertEquals(SubagentActivitySnapshot.REQUEST_UNKNOWN, snapshot.contextRequestState)
        assertFalse(entry.running.get())
    }

    @Test
    fun realContextSessionEventsUpdateTrackerAfterSuccessfulCompaction() {
        val tracker = tracker()
        val events = mutableListOf<AgentEvent.ContextCompaction>()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "previous task"))
            .put(JSONObject().put("role", "assistant").put("content", "old work ".repeat(1_000)))
            .put(JSONObject().put("role", "user").put("content", "current task"))
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "short summary").put("finish_reason", "stop"))
        }
        val session = AgentContextSession(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
                model = "fixture", systemPrompt = "system", contextWindow = 100_000),
            messages, 1, "operation", provider, AgentRunController(), { emptySet() },
            { event ->
                if (event is AgentEvent.ContextCompaction) events.add(event)
                tracker.onChildEvent(event)
            },
            {},
        )
        tracker.onChildEvent(usage(90_000))
        session.observeInputTokens(90_000)
        session.compact()
        assertEquals(listOf("started", "completed"), events.map { it.phase })
        assertNull(events.last().tokensAfter)
        assertNotNull(session.snapshot())
        assertEquals(1, tracker.snapshot().compactionCount)
        assertNull(tracker.snapshot().contextTokens)
        assertEquals(90_000, tracker.snapshot().contextReading?.tokens)
        assertEquals(true, tracker.snapshot().contextReading?.beforeCompaction)
    }

    @Test
    fun oldJsonDefaultsToUnknownAndBoundedPreviewKeepsStats() {
        val old = requireNotNull(SubagentActivitySnapshot.decode("""{"subagentId":"old","model":"model"}"""))
        assertNull(old.contextTokens)
        assertNull(old.contextWindow)
        assertNull(old.compactionCount)
        assertNull(old.contextReading)
        assertEquals(SubagentActivitySnapshot.REQUEST_UNKNOWN, old.contextRequestState)
        val oldWithValue = requireNotNull(SubagentActivitySnapshot.decode("""{"subagentId":"old","contextTokens":25000}"""))
        assertEquals(25_000, oldWithValue.contextTokens)
        assertNull(oldWithValue.contextReading)
        val snapshot = old.copy(
            description = "description".repeat(200),
            model = "model\\\"".repeat(300),
            prompt = "prompt\\\"".repeat(2_000),
            contextTokens = 25_000,
            contextWindow = 128_000,
            compactionCount = 3,
            contextReading = SubagentContextReading(25_000, at = 1_700_000_000_000L, beforeCompaction = true,
                previousInvocation = true, modelChanged = true, attempt = SubagentContextReading.ATTEMPT_FAILED),
            contextRequestState = SubagentActivitySnapshot.REQUEST_FAILED,
            steps = (1..20).map { SubagentStepSnapshot("$it", "tool", detail = "result\\\"".repeat(200)) },
        )
        assertEquals(snapshot, SubagentActivitySnapshot.decode(snapshot.encode()))
        for (limit in listOf(512, 2_000, 12_000)) {
            val encoded = snapshot.encodeBounded(limit)
            assertTrue(encoded.length <= limit)
            val preview = SubagentActivitySnapshot.decode(encoded)
            assertNotNull(preview)
            assertEquals(25_000, preview?.contextTokens)
            assertEquals(128_000, preview?.contextWindow)
            assertEquals(3, preview?.compactionCount)
            assertEquals(snapshot.contextReading, preview?.contextReading)
            assertEquals(snapshot.contextRequestState, preview?.contextRequestState)
            assertTrue(preview!!.previewTruncated)
            assertTrue(preview.model.isEmpty() || preview.model == snapshot.model)
        }
    }

    @Test
    fun trackerAccumulatesUsageAndDeduplicatesCompactionUsageByOperationId() {
        val tracker = tracker()
        assertNull(tracker.snapshot().usage)

        assertTrue(tracker.onChildEvent(AgentEvent.UsageReceived(
            round = 1,
            usage = AgentTokenUsage(
                contextTokens = 40_000,
                inputTokens = 1_000,
                cachedTokens = 500,
                cacheWriteTokens = 100,
                outputTokens = 80,
                reasoningTokens = 20,
                llmMs = 250L,
            ),
        )))
        assertEquals(
            SubagentUsageSnapshot(
                inputTokens = 1_000,
                cachedTokens = 500,
                cacheWriteTokens = 100,
                outputTokens = 80,
                reasoningTokens = 20,
                llmMs = 250L,
            ),
            tracker.snapshot().usage,
        )

        assertFalse(tracker.onChildEvent(AgentEvent.UsageReceived(
            round = 2,
            usage = AgentTokenUsage(
                contextTokens = null,
                inputTokens = 3_000,
                cachedTokens = 1_500,
                cacheWriteTokens = 200,
                outputTokens = 180,
                reasoningTokens = 45,
                stepCount = 2,
                llmMs = 600L,
            ),
        )))
        assertEquals(40_000, tracker.snapshot().contextTokens)

        val op1Usage = AgentTokenUsage(
            inputTokens = 800,
            cachedTokens = 400,
            cacheWriteTokens = 50,
            outputTokens = 90,
            reasoningTokens = 15,
            llmMs = 120L,
        )
        assertFalse(tracker.onChildEvent(AgentEvent.ContextCompaction(
            operationId = "op-1",
            phase = AgentEvent.ContextCompaction.PHASE_STARTED,
            tokensBefore = 40_000,
            usage = op1Usage,
        )))
        assertFalse(tracker.onChildEvent(AgentEvent.ContextCompaction(
            operationId = "op-fail",
            phase = "failed",
            tokensBefore = 40_000,
            usage = op1Usage,
        )))
        val completedOp1 = AgentEvent.ContextCompaction(
            operationId = "op-1",
            phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
            tokensBefore = 40_000,
            tokensAfter = 8_000,
            usage = op1Usage,
        )
        assertTrue(tracker.onChildEvent(completedOp1))
        assertFalse(tracker.onChildEvent(completedOp1))

        val op2Usage = AgentTokenUsage(
            inputTokens = 700,
            cachedTokens = 300,
            cacheWriteTokens = 25,
            outputTokens = 70,
            reasoningTokens = 10,
            llmMs = 80L,
        )
        assertTrue(tracker.onChildEvent(AgentEvent.ContextCompaction(
            operationId = "op-2",
            phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
            tokensBefore = 35_000,
            tokensAfter = 6_000,
            usage = op2Usage,
        )))
        // Non-adjacent duplicate completion for op-1 must also be ignored.
        assertFalse(tracker.onChildEvent(completedOp1))

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.compactionCount)
        assertEquals(6_000, snapshot.contextTokens)
        assertEquals(
            SubagentUsageSnapshot(
                inputTokens = 3_000 + 800 + 700,
                cachedTokens = 1_500 + 400 + 300,
                cacheWriteTokens = 200 + 50 + 25,
                outputTokens = 180 + 90 + 70,
                reasoningTokens = 45 + 15 + 10,
                llmMs = 600L + 120L + 80L,
            ),
            snapshot.usage,
        )
    }

    @Test
    fun realExecutorPublishesSummedChatAndCompactionUsageInSnapshot() {
        var chatRound = 0
        var compactionCalls = 0
        val publishedSnapshots = mutableListOf<SubagentActivitySnapshot>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                return if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    compactionCalls++
                    onEvent(ProviderEvent.Usage(AgentTokenUsage(
                        inputTokens = 12_000, cachedTokens = 8_000, cacheWriteTokens = 500,
                        outputTokens = 300, reasoningTokens = 60, llmMs = 150L,
                    )))
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "compact summary").put("finish_reason", "stop"))
                } else {
                    when (++chatRound) {
                        1 -> {
                            onEvent(ProviderEvent.Usage(AgentTokenUsage(
                                contextTokens = 90_000, inputTokens = 88_000, cachedTokens = 50_000,
                                cacheWriteTokens = 1_000, outputTokens = 200, reasoningTokens = 40,
                            )))
                            ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
                                    .put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
                        }
                        2 -> {
                            onEvent(ProviderEvent.Usage(AgentTokenUsage(
                                contextTokens = 10_000, inputTokens = 10_000, cachedTokens = 6_000,
                                cacheWriteTokens = 200, outputTokens = 150, reasoningTokens = 30,
                            )))
                            ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
                        }
                        else -> error("unexpected chat round $chatRound")
                    }
                }
            }
        }
        val executor = SubagentExecutor(
            conversationKey = "activity-stats-usage-regression",
            parentController = AgentRunController(),
            configProvider = {
                AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid", apiKey = "fixture", model = "model-a",
                    providerId = "provider-a", systemPrompt = "system",
                    contextWindow = 100_000, autoCompactionEnabled = true,
                )
            },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("file contents") },
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(),
            executionContext = null,
            onProgress = { SubagentActivitySnapshot.decode(it.snapshotJson)?.let(publishedSnapshots::add) },
            providerFactory = { provider },
        )
        val result = executor.execute(1, AgentModelClient.ToolCall("call-sub", "subagent", JSONObject()
            .put("subagent_type", "coder").put("prompt", "work").put("description", "usage regression").toString()))
        assertTrue(JSONObject(result.content).getBoolean("ok"))
        assertEquals(1, compactionCalls)
        val snapshot = requireNotNull(SubagentActivitySnapshot.decode(result.command))
        assertEquals(1, snapshot.compactionCount)
        assertEquals(10_000, snapshot.contextTokens)
        val usage = requireNotNull(snapshot.usage)
        assertEquals(110_000, usage.inputTokens)
        assertEquals(64_000, usage.cachedTokens)
        assertEquals(1_700, usage.cacheWriteTokens)
        assertEquals(650, usage.outputTokens)
        assertEquals(130, usage.reasoningTokens)
        assertTrue(usage.llmMs >= 150L)
        assertEquals(usage, publishedSnapshots.last().usage)
    }
}
