package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
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
        store.started("done", "sub_1", "研究问题")
        store.finished("done", longReport)
        store.started("running", "sub_2", "未完成问题")
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
        AgentSubagentCheckpoint(context, run).also { it.started("id", "sub_1", "任务"); it.finished("id", "完整报告") }
        val recovered = AgentRunCheckpointStore.list(context).single().transcript
        assertEquals(listOf("assistant", "tool", "tool", "assistant"), recovered.map { it.role })
        assertEquals("other", recovered[2].toolCallId)
        assertTrue(recovered[2].content.contains("TOOL_INTERRUPTED"))
        assertEquals("完整报告", recovered.last().content)
    }

    @Test fun externalInterruptedRunIsArchivedOnlyAfterKnownInactiveAndPreservesReport() {
        val run = "voice-interrupted"
        val payload = AgentExternalArchivePayload("原始问题", "voice-key", "语音会话").toJson()
        val request = AgentRuntimeWire.RunRequest(runId = run, prompt = "原始问题", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff(run, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, payload))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentSubagentCheckpoint(context, run).also { it.started("id", "sub_1", "任务"); it.finished("id", "完整最终报告") }
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
