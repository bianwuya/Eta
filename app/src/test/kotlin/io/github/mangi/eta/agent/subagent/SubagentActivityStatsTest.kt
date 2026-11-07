package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentContextSession
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
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
    ) = SubagentExecutor.StepTracker("child", "coder", "test", "prompt", "Readable Model", resumed,
        contextWindow = window, successfulCompactions = counter)

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
        assertFalse(tracker.onChildEvent(completed)) // A repeated completion is not another operation.
        assertEquals(1, tracker.snapshot().compactionCount)

        tracker.onChildEvent(usage(12_345))
        assertEquals(12_345, tracker.snapshot().contextTokens)
        tracker.onChildEvent(compaction("three", AgentEvent.ContextCompaction.PHASE_COMPLETED, 7_654))
        assertEquals(2, tracker.snapshot().compactionCount)
        assertEquals(7_654, tracker.snapshot().contextTokens)
        tracker.onChildEvent(usage(null))
        assertNull(tracker.snapshot().contextTokens)
        tracker.onChildEvent(usage(-1))
        assertNull(tracker.snapshot().contextTokens)
    }

    @Test
    fun retryAndResumeDoNotPresentOldUsageAsNewContext() {
        val conversation = "activity-stats-resume-test"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER)
        val first = tracker(entry.compactionCount)
        first.onChildEvent(usage(50_000))
        first.onChildEvent(compaction("first", AgentEvent.ContextCompaction.PHASE_COMPLETED, 9_000))
        first.onChildEvent(usage(15_000))
        first.onChildEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "STREAM_INCOMPLETE"))
        assertNull(first.snapshot().contextTokens)
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "done"))
        SubagentSessionStore.complete(conversation, entry.subagentId, history)

        val saved = requireNotNull(SubagentSessionStore.get(conversation, entry.subagentId))
        assertEquals(history, saved.history)
        assertTrue(saved.running.compareAndSet(false, true))
        val resumed = tracker(saved.compactionCount, resumed = true, window = 64_000)
        assertEquals(1, resumed.snapshot().compactionCount)
        assertEquals(64_000, resumed.snapshot().contextWindow)
        assertNull(resumed.snapshot().contextTokens)
        resumed.onChildEvent(compaction("second", AgentEvent.ContextCompaction.PHASE_COMPLETED, 4_000))
        assertEquals(2, resumed.snapshot().compactionCount)
        assertEquals(1, first.snapshot().compactionCount) // Prior invocation remains a snapshot.
        SubagentSessionStore.releaseRunning(conversation, entry.subagentId)
        assertEquals(2, SubagentSessionStore.get(conversation, entry.subagentId)?.compactionCount?.get())
        assertEquals(0, tracker().snapshot().compactionCount) // Another child is independent.
    }

    @Test
    fun configFailureOnRealResumePreservesTheKnownCount() {
        val conversation = "activity-stats-failed-config-test"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER)
        entry.compactionCount.set(2)
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
    }

    @Test
    fun oldJsonDefaultsToUnknownAndBoundedPreviewKeepsStats() {
        val old = requireNotNull(SubagentActivitySnapshot.decode("""{"subagentId":"old","model":"model"}"""))
        assertNull(old.contextTokens)
        assertNull(old.contextWindow)
        assertNull(old.compactionCount)
        val snapshot = old.copy(
            description = "description".repeat(200),
            model = "model\\\"".repeat(300),
            prompt = "prompt\\\"".repeat(2_000),
            contextTokens = 25_000,
            contextWindow = 128_000,
            compactionCount = 3,
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
            assertTrue(preview!!.previewTruncated)
            assertTrue(preview.model.isEmpty() || preview.model == snapshot.model)
        }
    }
}
