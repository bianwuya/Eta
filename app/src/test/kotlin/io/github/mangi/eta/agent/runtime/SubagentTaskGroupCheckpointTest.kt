package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
class SubagentTaskGroupCheckpointTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
    }
    private fun records(run: String): JSONArray = JSONArray(text("runtime_subagent_tasks_v1", run, "tasks"))
    private fun text(table: String, owner: String, field: String): String = runBlocking {
        EtaDatabase.get(context).runtimeRunDao().textChunks(table, owner, field, Int.MAX_VALUE, 0).joinToString("") { it.content }
    }
    private fun task(run: String, id: String) = records(run).let { values ->
        (0 until values.length()).map(values::getJSONObject).single { it.optString("task_id") == id }
    }
    private fun group(run: String) = records(run).let { values ->
        (0 until values.length()).map(values::getJSONObject).single { it.optString("kind") == "group" }
    }
    private fun semantic(state: String) = SubagentTaskResult.fromFinal("task_status: $state\nsummary: summary-$state\n" +
        "evidence: Source:1\nremaining: ${if (state in setOf("partial", "blocked")) "verify" else "-"}\nnext_action: -")
    private fun start(store: AgentSubagentCheckpoint, id: String, round: Int = 1) {
        store.reserveGroupMember(id, "call-$id", round, "inspect", "batch")
        store.started(id, "call-$id", round, "same-child", "inspect", JSONObject().put("ok", true)
            .put("status", "accepted").put("task_id", id).put("subagent_id", "same-child").put("group_key", "batch").toString())
    }

    @Test fun membershipAndSealPersistAcrossStoreReopenAndFullMembersRemainSeparate() {
        val run = "groups-reopen"; var store = AgentSubagentCheckpoint(context, run)
        start(store, "a")
        val fullA = "FULL-A-" + "🧪".repeat(80_000)
        assertTrue(store.finished("a", fullA, semantic("completed"), true, "completed"))
        assertEquals("open", group(run).getString("state"))
        store = AgentSubagentCheckpoint(context, run)
        start(store, "b", 3)
        store.sealGroups(listOf("batch")); assertEquals("sealed", group(run).getString("state"))
        assertThrows(IllegalStateException::class.java) { store.reserveGroupMember("late", "call-late", 4, "inspect", "batch") }
        assertTrue(store.finished("b", "FULL-B", semantic("blocked"), false, "completed"))
        assertEquals("ready", group(run).getString("state"))
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList()).single()
        assertEquals(SubagentTaskGroup.messageId(run, "batch"), recovered.messageId)
        assertFalse(recovered.content.contains(fullA)); assertFalse(recovered.content.contains("FULL-B"))
        assertTrue(recovered.content.contains("task_status: blocked"))
        assertEquals(fullA, task(run, "a").getString("report"))
        assertEquals(fullA, text(SubagentTaskGroup.REPORT_TABLE, "a", "report"))
        assertEquals("FULL-B", text(SubagentTaskGroup.REPORT_TABLE, "b", "report"))
        assertEquals("call-b", JSONObject(text(SubagentTaskGroup.REPORT_TABLE, "b", "metadata")).getString("tool_call_id"))
    }

    @Test fun crashBeforeSealSettlesReservedAndRunningInvocationsWithoutReplay() {
        val run = "crash-open"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "done"); store.finished("done", "FULL-DONE", semantic("partial"), false, "completed")
        start(store, "running", 2)
        store.reserveGroupMember("reserved", "call-reserved", 3, "not accepted", "batch")
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(1, recovered.size)
        assertTrue(recovered.single().content.contains("engine_status: interrupted"))
        assertEquals(3, Regex("full_report_ref:").findAll(recovered.single().content).count())
        assertEquals("ready", group(run).getString("state"))
        for (id in listOf("running", "reserved")) {
            assertEquals("finished", task(run, id).getString("state"))
            assertEquals("unknown", task(run, id).getString("task_status"))
            assertFalse(task(run, id).getBoolean("succeeded"))
            assertTrue(text(SubagentTaskGroup.REPORT_TABLE, id, "report").contains("不自动重跑"))
        }
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, emptyList()))
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered))
        assertEquals("delivered", group(run).getString("state"))
    }

    @Test fun transcriptCommitBeforeConsumptionDeduplicatesAndConsumptionWithoutTranscriptRetriesStableId() {
        val run = "delivery-windows"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "a"); store.finished("a", "ORIGINAL", semantic("completed"), true, "completed")
        store.sealGroups(listOf("batch"))
        val first = AgentSubagentCheckpoint.recover(context, run, emptyList())
        // Crash after transcript commit, before owner.consumed(): transcript is authoritative.
        assertEquals(first, AgentSubagentCheckpoint.recover(context, run, first))
        assertEquals("delivered", group(run).getString("state"))
        store.consumed(first.map { it.messageId })
        // Missing transcript must not trust only the delivered checkpoint bit and lose output.
        val retry = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(first, retry)
        assertEquals(first, AgentSubagentCheckpoint.recover(context, run, retry))
    }

    @Test fun recoveryMixedUngroupedAndGroupedKeepsLegacyFullOutputAndOneJointMessage() {
        val run = "mixed"; val store = AgentSubagentCheckpoint(context, run)
        store.started("legacy", "legacy-call", 1, "legacy-child", "legacy", "accepted")
        store.finished("legacy", "LEGACY-FULL")
        start(store, "grouped", 2); store.finished("grouped", "GROUP-FULL", semantic("completed"), true, "completed")
        start(store, "pending", 3)
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(2, recovered.size)
        assertEquals("LEGACY-FULL", recovered.first().content)
        assertEquals(SubagentTaskGroup.messageId(run, "batch"), recovered.last().messageId)
        assertFalse(recovered.last().content.contains("GROUP-FULL"))
        repeat(3) { assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered)) }
    }

    @Test fun previewDoesNotMutateLiveGroupAndFinalRecoveryPersistsBeforeOutput() {
        val run = "active-preview"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "a")
        assertTrue(AgentSubagentCheckpoint.recover(context, run, emptyList(), finalize = false).isEmpty())
        assertEquals("running", task(run, "a").getString("state")); assertEquals("open", group(run).getString("state"))
        assertTrue(text(SubagentTaskGroup.REPORT_TABLE, "a", "report").isEmpty())
        start(store, "b", 2)
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(1, recovered.size); assertEquals("ready", group(run).getString("state"))
        assertTrue(text(SubagentTaskGroup.REPORT_TABLE, "a", "report").contains("interrupted"))
    }

    @Test fun stopSealsAllFinishedOpenGroupAndRejectsLateCompletion() {
        val run = "stopped-open"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "a"); store.finished("a", "COMPLETED-BEFORE-STOP", semantic("completed"), true, "completed")
        start(store, "b", 2); store.interrupted()
        assertEquals("ready", group(run).getString("state"))
        assertFalse(store.finished("b", "LATE-FALSE-SUCCESS", semantic("completed"), true, "completed"))
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList()).single()
        assertTrue(recovered.content.contains("task_id: a")); assertTrue(recovered.content.contains("task_id: b"))
        assertEquals("COMPLETED-BEFORE-STOP", text(SubagentTaskGroup.REPORT_TABLE, "a", "report"))
        assertFalse(text(SubagentTaskGroup.REPORT_TABLE, "b", "report").contains("LATE-FALSE-SUCCESS"))
    }

    @Test fun stoppedAllFinishedOpenGroupStillProducesExactlyOneJointReport() {
        val run = "all-finished-open"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "done"); store.finished("done", "ORIGINAL", semantic("completed"), true, "completed")
        assertEquals("open", group(run).getString("state")); store.interrupted()
        assertEquals("ready", group(run).getString("state"))
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(1, recovered.size); assertTrue(recovered.single().content.contains("succeeded: true"))
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, run, recovered))
    }

    @Test fun parentRunIsolationAndRepeatedResumedChildHaveDistinctInvocationReports() {
        for (run in listOf("parent-one", "parent-two")) {
            val store = AgentSubagentCheckpoint(context, run)
            for (index in 1..2) { start(store, "$run-$index", index); store.finished("$run-$index", "$run-full-$index", semantic("completed"), true, "completed") }
            store.sealGroups(listOf("batch"))
        }
        val one = AgentSubagentCheckpoint.recover(context, "parent-one", emptyList()).single()
        val two = AgentSubagentCheckpoint.recover(context, "parent-two", emptyList()).single()
        assertNotEquals(one.messageId, two.messageId)
        assertFalse(one.content.contains("task_id: parent-two"))
        assertEquals(2, Regex("subagent_id: same-child").findAll(one.content).count())
        assertTrue(one.content.contains("task_id: parent-one-1")); assertTrue(one.content.contains("task_id: parent-one-2"))
    }

    @Test fun fullMemberRefsSurviveRuntimeAcknowledgementCleanup() {
        val run = "cleanup-retention"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "a"); store.finished("a", "PERSISTED-FULL", semantic("completed"), true, "completed")
        store.sealGroups(listOf("batch")); val report = AgentSubagentCheckpoint.recover(context, run, emptyList()).single()
        runBlocking { EtaDatabase.get(context).runtimeRunDao().removeInFlightRun(run) }
        assertTrue(text("runtime_subagent_tasks_v1", run, "tasks").isEmpty())
        assertEquals("PERSISTED-FULL", text(SubagentTaskGroup.REPORT_TABLE, "a", "report"))
        assertTrue(report.content.contains("${SubagentTaskGroup.REPORT_TABLE}/a/report"))
    }

    @Test fun recoveredJointReportAppendsOnlyAfterEveryOriginalToolResultIsPaired() {
        val run = "signed-pairing"; val store = AgentSubagentCheckpoint(context, run)
        start(store, "a"); start(store, "b")
        val calls = JSONArray(listOf("a", "b", "other").map { id -> JSONObject().put("id", "call-$id")
            .put("type", "function").put("function", JSONObject().put("name", if (id == "other") "fixture" else "subagent").put("arguments", "{}")) })
        val declaration = AgentModelClient.ConversationMessage("assistant", messageId = "assistant-$run-1", toolCallsJson = calls.toString())
        val recovered = AgentSubagentCheckpoint.recover(context, run, listOf(declaration))
        assertEquals(listOf("assistant", "tool", "tool", "tool", "assistant"), recovered.map { it.role })
        assertTrue(recovered[1].content.contains("accepted")); assertTrue(recovered[2].content.contains("accepted"))
        assertTrue(recovered[3].content.contains("TOOL_INTERRUPTED"))
        assertEquals(SubagentTaskGroup.messageId(run, "batch"), recovered.last().messageId)
    }
}
