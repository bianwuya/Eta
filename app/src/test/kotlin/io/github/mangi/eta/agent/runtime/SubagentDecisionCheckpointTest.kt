package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.subagent.SubagentDecisionBroker
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentDecisionCheckpointTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    private fun read() = runBlocking { EtaDatabase.get(context).runtimeRunDao()
        .textChunks(SubagentDecisionBroker.TABLE, "run", "requests", Int.MAX_VALUE, 0).joinToString("") { it.content } }
    @Test fun committedRecordsSurviveReopenAndInterruptedRecoveryDoesNotReplay() {
        val store = AgentSubagentCheckpoint(context, "run")
        store.accessRequests { records -> records.put(JSONObject().put("request_id", "r").put("status", "pending")); JSONObject() }
        val original = read(); assertTrue(original.contains("pending"))
        EtaDatabase.closeForTests()
        val reopened = AgentSubagentCheckpoint(context, "run")
        assertEquals(1, reopened.accessRequests { records -> JSONObject().put("size", records.length()) }.getInt("size"))
        val preview = AgentSubagentCheckpoint.recover(context, "run", emptyList(), finalize = false)
        assertTrue(preview.isEmpty())
        assertEquals(original, read())
        val recovered = AgentSubagentCheckpoint.recover(context, "run", preview)
        assertTrue(recovered.isEmpty())
        assertEquals("interrupted", org.json.JSONArray(read()).getJSONObject(0).getString("status"))
        val recoveredAgain = AgentSubagentCheckpoint.recover(context, "run", recovered)
        assertEquals(recovered, recoveredAgain)
        assertEquals(1, org.json.JSONArray(read()).length())
        runBlocking { EtaDatabase.get(context).runtimeRunDao().removeInFlightRun("run") }
        assertTrue(read().isEmpty())
        runBlocking { EtaDatabase.get(context).runtimeRunDao().removeInFlightRun("run") }
        assertTrue(read().isEmpty())
    }
    @Test fun failedTransactionDoesNotExposePartialMutation() {
        val store = AgentSubagentCheckpoint(context, "run")
        store.accessRequests { values -> values.put(JSONObject().put("request_id", "first")); JSONObject() }
        val before = read()
        assertThrows(IllegalStateException::class.java) {
            store.accessRequests { values -> values.put(JSONObject().put("request_id", "not-committed")); error("fixture") }
        }
        assertEquals(before, read())
    }
    @Test fun responseCommitUnknownDoesNotClaimAcceptanceAndRecoveryDoesNotReplayAsk() {
        val actual = AgentSubagentCheckpoint(context, "run")
        val failAfterCommit = java.util.concurrent.atomic.AtomicBoolean(false)
        val wrapped = object : SubagentDecisionBroker.Storage {
            override fun accessRequests(operation: (org.json.JSONArray) -> JSONObject): JSONObject {
                val result = actual.accessRequests(operation)
                if (failAfterCommit.compareAndSet(true, false)) error("simulated lost commit acknowledgement")
                return result
            }
        }
        val broker = SubagentDecisionBroker("run", AgentRunController(), wrapped, timeoutMs = 5000)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val ask = io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("original-call", "ask_parent",
                JSONObject().put("request_id", "unknown").put("question", "critical").toString())
            val child = pool.submit(java.util.concurrent.Callable { broker.ask("task", "invocation", ask, AgentRunController()) })
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4)
            var batch = broker.pendingBatch()
            while (batch.isEmpty() && System.nanoTime() < until) { Thread.sleep(5); batch = broker.pendingBatch() }
            assertEquals(1, batch.size)
            val record = batch.single()
            val response = io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("response", "subagent_respond", JSONObject()
                .put("request_id", "unknown").put("task_id", record.getString("task_id"))
                .put("invocation_id", record.getString("invocation_id")).put("tool_call_id", record.getString("tool_call_id"))
                .put("action", "answer").put("response", "chosen").toString())
            failAfterCommit.set(true)
            val outcome = JSONObject(broker.respond(response).content)
            assertFalse(outcome.optBoolean("ok"))
            assertEquals("ASK_STORAGE_UNKNOWN", outcome.getString("code"))
            // The actual Room commit happened, but the caller did not receive its receipt.
            val raw = read()
            assertEquals("answer", org.json.JSONArray(raw).getJSONObject(0).getString("status"))
            broker.close()
            child.get(4, java.util.concurrent.TimeUnit.SECONDS)
            EtaDatabase.closeForTests()
            val preview = AgentSubagentCheckpoint.recover(context, "run", emptyList(), finalize = false)
            val finalized = AgentSubagentCheckpoint.recover(context, "run", preview, finalize = true)
            assertTrue(finalized.none { it.content.contains("critical") || it.toolCallId == "original-call" })
            assertEquals("answer", org.json.JSONArray(read()).getJSONObject(0).getString("status"))
            assertTrue(AgentSubagentCheckpoint.recover(context, "run", finalized, finalize = true).none {
                it.content.contains("critical") || it.toolCallId == "original-call"
            })
        } finally { broker.close(); pool.shutdownNow() }
    }

}
