package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.subagent.YieldableSubagents
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YieldableSubagentCheckpointTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test fun recoveryKeepsFullCompletedReportsMarksRunningInterruptedAndDeduplicates() {
        val run = "run-children"
        val store = AgentSubagentCheckpoint(context, run)
        val longReport = "结论🧪".repeat(50_000)
        store.started("done", "call-done", 1, "sub_1", "研究问题", "accepted-done")
        store.finished("done", longReport)
        store.started("running", "call-running", 1, "sub_2", "未完成问题", "accepted-running")
        val old = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "旧前缀"))
        val recovered = AgentSubagentCheckpoint.recover(context, run, old)
        assertEquals(old, recovered.take(1))
        assertEquals(longReport, recovered[1].content)
        assertEquals("subagent-report-done", recovered[1].messageId)
        assertTrue(recovered[2].content.contains("interrupted"))
        assertTrue(recovered[2].content.contains("不自动重跑"))
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered))
        store.interrupted()
        store.finished("running", "late result must not replace interruption")
        val stopped = AgentSubagentCheckpoint.recover(context, run, old)
        assertTrue(stopped.last().content.contains("interrupted"))
        assertFalse(stopped.last().content.contains("late result"))
        AgentSubagentCheckpoint.remove(context, run)
        assertEquals(old, AgentSubagentCheckpoint.recover(context, run, old))
    }

    @Test fun semanticResultsAreAtomicWithFullReportsAndResumeTaskIdsRemainDistinct() {
        val run = "semantic-recovery"
        val store = AgentSubagentCheckpoint(context, run)
        val statuses = listOf("blocked", "completed")
        statuses.forEachIndexed { index, status ->
            val id = "task-$index"
            val remaining = if (status == "completed") "-" else "verify"
            val semantic = io.github.mangi.eta.local.subagentcontext.SubagentTaskResult.fromFinal(
                "task_status: $status\nsummary: result-$index\nevidence: Source:1\nremaining: $remaining\nnext_action: -")
            store.started(id, "call-$index", index + 1, "same-resumed-child", "inspect", "accepted-$index")
            val full = "full-report-$index " + "x".repeat(150_000)
            assertTrue(store.finished(id, full, semantic))
            assertFalse(store.finished(id, "late-duplicate", semantic))
        }
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(listOf("subagent-report-task-0", "subagent-report-task-1"), recovered.map { it.messageId })
        assertTrue(recovered.all { it.content.length > 150_000 })
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered))
        val dao = EtaDatabase.get(context).runtimeRunDao()
        val raw = runBlocking { dao.textChunks("runtime_subagent_tasks_v1", run, "tasks", Int.MAX_VALUE, 0).joinToString("") { it.content } }
        val stored = org.json.JSONArray(raw)
        statuses.forEachIndexed { index, status ->
            assertEquals(status, stored.getJSONObject(index).getString("task_status"))
            assertEquals(status, stored.getJSONObject(index).getJSONObject("result").getString("status"))
            assertTrue(stored.getJSONObject(index).getString("report").contains("full-report-$index"))
        }
    }

    @Test fun transcriptWriteReportsMissingInFlightRowAndPreservesCommittedSnapshot() {
        val run = "missing-transcript-row"
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        val session = AgentRuntimeSession(run)
        val previous = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "已保存"))
        session.commitTranscript(previous) { check(AgentRunCheckpointStore.saveTranscript(context, run, previous)) }
        AgentRunCheckpointStore.remove(context, run)
        val pending = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "工具声明"))
        assertFalse(AgentRunCheckpointStore.saveTranscript(context, run, pending))
        assertThrows(IllegalStateException::class.java) {
            session.commitTranscript(pending) { check(AgentRunCheckpointStore.saveTranscript(context, run, pending)) }
        }
        assertEquals(previous, session.transcript)
    }

    @Test fun checkpointListPreservesBatchAndAppendsRecoveredReportAfterToolPairing() {
        val run = "run-pairing"
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        val calls = """[{"id":"child","type":"function","function":{"name":"subagent","arguments":"{}"}},{"id":"other","type":"function","function":{"name":"x","arguments":"{}"}}]"""
        AgentRunCheckpointStore.saveTranscript(context, run, listOf(
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = calls),
            AgentModelClient.ConversationMessage(role = "tool", toolCallId = "child", content = "accepted"),
        ))
        AgentSubagentCheckpoint(context, run).also { it.started("id", "child", 1, "sub_1", "任务", "accepted"); it.finished("id", "完整报告") }
        val recovered = AgentRunCheckpointStore.list(context).single().transcript
        assertEquals(listOf("assistant", "tool", "tool", "assistant"), recovered.map { it.role })
        assertEquals("other", recovered[2].toolCallId)
        assertTrue(recovered[2].content.contains("TOOL_INTERRUPTED"))
        assertEquals("完整报告", recovered.last().content)
    }

    @Test fun recoveredAcceptedMatchesItsBatchAndKeepsCompletedOrdinaryToolResult() {
        val run = "batch-recovery"
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        val calls = """[{"id":"done","type":"function","function":{"name":"x","arguments":"{}"}},{"id":"child","type":"function","function":{"name":"subagent","arguments":"{}"}},{"id":"unknown","type":"function","function":{"name":"x","arguments":"{}"}}]"""
        val assistant = AgentModelClient.ConversationMessage(role = "assistant", messageId = "assistant-$run-2", toolCallsJson = calls)
        val done = AgentModelClient.ConversationMessage(role = "tool", toolCallId = "done", content = "真实普通工具结果")
        AgentRunCheckpointStore.saveTranscript(context, run, listOf(assistant, done))
        val accepted = "{\"ok\":true,\"status\":\"accepted\",\"task_id\":\"task\"}"
        AgentSubagentCheckpoint(context, run).started("task", "child", 2, "sub_1", "任务", accepted)
        val recovered = AgentRunCheckpointStore.list(context).single().transcript
        assertEquals(done, recovered[1])
        assertEquals("child", recovered[2].toolCallId)
        assertEquals(accepted, recovered[2].content)
        assertEquals("unknown", recovered[3].toolCallId)
        assertTrue(recovered[3].content.contains("TOOL_INTERRUPTED"))
        assertTrue(recovered.last().content.contains("interrupted"))
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered))
    }

    @Test fun acceptedReceiptCannotBeMatchedToSameCallIdFromAnotherRound() {
        val run = "round-scope"
        val calls = """[{"id":"reused","type":"function","function":{"name":"subagent","arguments":"{}"}}]"""
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", messageId = "assistant-$run-1", toolCallsJson = calls),
        )
        AgentSubagentCheckpoint(context, run).started("task", "reused", 2, "sub_1", "任务", "accepted-round-2")
        val recovered = AgentSubagentCheckpoint.recover(context, run, transcript)
        assertTrue(recovered[1].content.contains("TOOL_INTERRUPTED"))
        assertFalse(recovered[1].content.contains("accepted-round-2"))
    }

    @Test fun cleanupRollbackLeavesInflightAndChildRecordsTogether() {
        val run = "atomic-cleanup"
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentSubagentCheckpoint(context, run).also { it.started("id", "child", 1, "sub_1", "任务", "accepted"); it.finished("id", "已保存报告") }
        val db = EtaDatabase.get(context)
        runBlocking {
            try { db.withTransaction { db.runtimeRunDao().removeInFlightRun(run); error("rollback") } }
            catch (_: IllegalStateException) { }
        }
        assertEquals("已保存报告", AgentRunCheckpointStore.list(context).single().transcript.last().content)
        AgentRunCheckpointStore.remove(context, run)
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        assertTrue(AgentSubagentCheckpoint.recover(context, run, emptyList()).isEmpty())
    }

    @Test fun outboxCleanupRollbackKeepsOutboxAndChildRecordsTogether() {
        val run = "atomic-outbox"
        val handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation")
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), handoff = handoff)
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentSubagentCheckpoint(context, run).started("id", "child", 1, "sub_1", "任务", "accepted")
        assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff,
            AgentRuntimeWire.RunResult(run, true, "done"), 1L)))
        val db = EtaDatabase.get(context)
        runBlocking {
            try { db.withTransaction { db.runtimeRunDao().acknowledgeRuntimeResult(run); error("rollback") } }
            catch (_: IllegalStateException) { }
        }
        assertTrue(AgentRuntimeResultStore.list(context).any { it.result.runId == run })
        assertEquals(1, AgentSubagentCheckpoint.recover(context, run, emptyList()).size)
        AgentRuntimeResultStore.remove(context, run)
        assertTrue(AgentSubagentCheckpoint.recover(context, run, emptyList()).isEmpty())
    }

    @Test fun outboxBeyondFirstPageDoesNotBecomeAnInterruptedExternalArchive() {
        val run = "voice-ninth"
        val payload = AgentExternalArchivePayload("原始问题", "key", "语音").toJson()
        val handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, payload)
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "test", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), handoff = handoff)
        assertTrue(AgentRunCheckpointStore.start(context, request))
        repeat(8) { i ->
            val id = "earlier-$i"
            val h = AgentRuntimeWire.EntryHandoff(id, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation")
            assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(h, AgentRuntimeWire.RunResult(id, true, "done"), i.toLong())))
        }
        assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff, AgentRuntimeWire.RunResult(run, true, "done"), 100L)))
        assertFalse(AgentRuntimeResultStore.pendingPage(context).any { it.result.runId == run })
        AgentRunCheckpointStore.archiveInterruptedExternal(context, AgentRunCheckpointStore.list(context), emptySet(), emptySet())
        assertTrue(AgentRunArchiveStore.list(context).isEmpty())
        assertTrue(AgentRunCheckpointStore.list(context).any { it.runId == run })
    }

    @Test fun pausePropagatesToExistingAndNewChildrenWithoutCancellingInflightIo() {
        val parent = AgentRunController(); val group = AgentRunController(); val child = AgentRunController()
        val parentBinding = parent.registerChild(group); val childBinding = group.registerChild(child)
        val cancels = java.util.concurrent.atomic.AtomicInteger()
        child.register { cancels.incrementAndGet() }
        parent.pause()
        assertTrue(group.isPaused); assertTrue(child.isPaused)
        val late = AgentRunController(); val lateBinding = group.registerChild(late)
        assertTrue(late.isPaused)
        val released = CountDownLatch(1)
        val thread = Thread { child.throwIfCancelled(); released.countDown() }
        thread.start(); assertFalse(released.await(150, TimeUnit.MILLISECONDS)); assertEquals(0, cancels.get())
        parent.resume(); assertTrue(released.await(2, TimeUnit.SECONDS)); thread.join(2000)
        assertFalse(late.isPaused); assertEquals(0, cancels.get())
        parent.cancel(); assertEquals(1, cancels.get()); assertTrue(late.isCancelled)
        childBinding.close(); lateBinding.close(); parentBinding.close()
    }

    @Test fun cancelWaitsForAtomicTranscriptCommitBeforeTakingRecoverySnapshot() {
        val writing = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val expected = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "完整报告"))
        var delivered: AgentRuntimeWire.RunResult? = null
        val target = AgentRuntimeSession("snapshot-race", resultSink = { delivered = it })
        val writer = Thread { target.commitTranscript(expected) { writing.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
        val canceller = Thread { target.cancel("stop"); done.countDown() }
        writer.start(); assertTrue(writing.await(2, TimeUnit.SECONDS)); canceller.start()
        assertFalse(done.await(150, TimeUnit.MILLISECONDS)); release.countDown()
        writer.join(2000); canceller.join(2000)
        assertEquals(expected, delivered!!.transcript)
    }

    @Test fun externalInterruptedRunIsArchivedOnlyAfterKnownInactiveAndPreservesReport() {
        val run = "voice-interrupted"
        val payload = AgentExternalArchivePayload("原始问题", "voice-key", "语音会话").toJson()
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "原始问题", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, payload))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentSubagentCheckpoint(context, run).also { it.started("id", "child", 1, "sub_1", "任务", "accepted"); it.finished("id", "完整最终报告") }
        val checkpoints = AgentRunCheckpointStore.list(context)
        AgentRunCheckpointStore.archiveInterruptedExternal(context, checkpoints, setOf(run), emptySet())
        assertTrue(AgentRunArchiveStore.list(context).isEmpty())
        AgentRunCheckpointStore.archiveInterruptedExternal(context, checkpoints, emptySet(), setOf(run))
        assertTrue(AgentRunArchiveStore.list(context).isEmpty())
        AgentRunCheckpointStore.archiveInterruptedExternal(context, checkpoints, emptySet(), emptySet())
        val result = AgentRunArchiveStore.list(context).single().result
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("中断"))
        assertEquals("完整最终报告", result.transcript.last().content)
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
    }

    @Test fun acceptedChildCancellationCannotWinAsFinishedBeforeInterruptedCallback() {
        val run = "cancel-gap"
        val parent = AgentRunController()
        val child = AgentRunController()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val workerReturned = CountDownLatch(1)
        child.register { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val binding = parent.registerChild(child)
        val store = AgentSubagentCheckpoint(context, run) { parent.isCancelled }
        store.started("prior", "earlier", 1, "sub_0", "先前任务", "accepted")
        assertTrue(store.finished("prior", "取消前已完成"))
        val owner = YieldableSubagents(parent, store) { _, start ->
            start("sub_1", "")
            check(entered.await(5, TimeUnit.SECONDS))
            workerReturned.countDown()
            AgentModelClient.ToolResult("""{"ok":false,"code":"SUBAGENT_START_FAILED"}""")
        }
        val ack = org.json.JSONObject(owner.launch(AgentModelClient.ToolCall("child", "subagent", """{"prompt":"任务","description":"检查"}""")).content)
        val task = ack.getString("task_id")
        val canceller = Thread { parent.cancel() }
        try {
            canceller.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(workerReturned.await(5, TimeUnit.SECONDS))
            assertTrue(parent.isCancelled)
            // The parent's sidecar callback is still blocked behind child.cancel().
            assertFalse(store.finished(task, "SUBAGENT_START_FAILED"))
            assertThrows(AgentRunCancelledException::class.java) {
                store.started("late", "late-call", 2, "sub_2", "晚启动", "accepted")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.decision() != YieldableSubagents.Decision.FINISH && System.nanoTime() < deadline) Thread.yield()
            assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
            assertTrue(owner.peekReports().isEmpty())
            val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
            assertEquals("取消前已完成", recovered.first().content)
            assertTrue(recovered.last().content.contains("interrupted"))
            assertFalse(recovered.last().content.contains("SUBAGENT_START_FAILED"))
        } finally {
            release.countDown(); canceller.join(5000); owner.close(); binding.close()
        }
        val after = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertTrue(after.last().content.contains("interrupted"))
        assertFalse(after.last().content.contains("SUBAGENT_START_FAILED"))
    }

    @Test fun abortWithLiveParentDoesNotQueueCancelledChildAsFinished() {
        val root = AgentRunController(); val group = AgentRunController()
        val store = AgentSubagentCheckpoint(context, "abort-live-parent") { root.isCancelled }
        val cancelled = CountDownLatch(1)
        val owner = YieldableSubagents(root, store, cancelChildren = group::cancel) { _, start ->
            val binding = group.register { cancelled.countDown() }
            try {
                start("sub_1", "")
                check(cancelled.await(5, TimeUnit.SECONDS))
                group.throwIfCancelled()
                AgentModelClient.ToolResult("unexpected")
            } finally { binding.close() }
        }
        try {
            val ack = owner.launch(AgentModelClient.ToolCall("child", "subagent", """{"prompt":"任务","description":"检查"}"""))
            assertEquals("accepted", org.json.JSONObject(ack.content).getString("status"))
            owner.abort(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertFalse(root.isCancelled)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.decision() != YieldableSubagents.Decision.FINISH && System.nanoTime() < deadline) Thread.yield()
            assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
            assertTrue(owner.peekReports().isEmpty())
            val recovered = AgentSubagentCheckpoint.recover(context, "abort-live-parent", emptyList()).single()
            assertTrue(recovered.content.contains("interrupted"))
            assertFalse(recovered.content.contains("SUBAGENT_START_FAILED"))
        } finally { owner.close(); group.cancel() }
    }

    @Test fun boundaryCollectsAllAlreadyArrivedGuidanceAndReportsInOrder() {
        val controller = AgentRunController()
        assertTrue(controller.steer("先前方向"))
        assertTrue(controller.steer("最新方向"))
        val batch = controller.collectBoundaryInputs { listOf("report-two", "report-one") }
        assertEquals(listOf("先前方向", "最新方向"), batch.steering.map { it.text })
        assertEquals(listOf("report-two", "report-one"), batch.reports)
        assertFalse(controller.hasPendingSteering)
    }

    @Test fun pausedWaiterIgnoresSteeringUntilResumeAndStopWakesWithoutPolling() {
        val controller = AgentRunController()
        controller.pause()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val waiter = Thread { entered.countDown(); controller.awaitWorkAvailable { false }; returned.countDown() }
        waiter.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(controller.steer("引导"))
        assertFalse(returned.await(150, TimeUnit.MILLISECONDS))
        controller.resume()
        assertTrue(returned.await(2, TimeUnit.SECONDS))
        assertEquals("引导", controller.pollSteeringMessage())
        waiter.join(2000)

        val cancelled = AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        val stopped = Thread {
            waiting.countDown()
            try { controller.awaitWorkAvailable { false } } catch (_: AgentRunCancelledException) { cancelled.set(true) }
        }
        stopped.start(); assertTrue(waiting.await(2, TimeUnit.SECONDS)); controller.cancel(); stopped.join(2000)
        assertFalse(stopped.isAlive); assertTrue(cancelled.get())
    }
}
