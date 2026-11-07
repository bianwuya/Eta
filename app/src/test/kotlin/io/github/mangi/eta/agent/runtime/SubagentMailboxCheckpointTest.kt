package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.local.subagentcontext.SubagentMailbox
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
class SubagentMailboxCheckpointTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
    }
    private fun member(store: AgentSubagentCheckpoint, run: String, id: String, group: String = "batch",
        allowed: Boolean = true, active: () -> Boolean = { true }): SubagentMailbox.Identity {
        store.reserveGroupMember(id, "call-$id", 1, "fixture", group)
        val identity = SubagentMailbox.Identity(SubagentMailbox.Invocation(SubagentMailbox.Scope(run, group), id), "sub-$id")
        store.bind(identity, allowed, active)
        store.started(id, "call-$id", 1, identity.subagentId, "fixture", "accepted")
        return identity
    }
    private fun execute(store: AgentSubagentCheckpoint, identity: SubagentMailbox.Identity, args: JSONObject) =
        JSONObject(SubagentMailbox(identity, store) {}.execute(AgentModelClient.ToolCall("paired", "agent_mailbox", args.toString())).content)
    private fun send(id: String = "info", text: String = "EVIDENCE") = JSONObject().put("action", "send")
        .put("message_id", id).put("receiver", "b").put("content", text)
    private fun list() = JSONObject().put("action", "list")
    private fun read() = JSONObject().put("action", "read").put("message_id", "info")
    private fun raw(run: String, table: String = SubagentMailbox.TABLE) = runBlocking {
        EtaDatabase.get(context).runtimeRunDao().textChunks(table, run, if (table == SubagentMailbox.TABLE) "messages" else "tasks",
            Int.MAX_VALUE, 0).joinToString("") { it.content }
    }

    @Test fun committedSendDuplicateSequenceAndReadSurviveDatabaseCloseAndReopen() {
        val run = "reopen"; var store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); val b = member(store, run, "b")
        val first = execute(store, a, send()).getJSONObject("receipt")
        assertEquals(1L, first.getLong("sequence")); assertEquals("STORED", first.getString("status"))
        EtaDatabase.closeForTests(); store = AgentSubagentCheckpoint(context, run)
        val duplicate = execute(store, a, send())
        assertTrue(duplicate.getBoolean("duplicate"))
        assertEquals(first.toString(), duplicate.getJSONObject("receipt").toString())
        assertEquals("MAILBOX_MESSAGE_CONFLICT", execute(store, a, send(text = "changed")).getString("code"))
        assertEquals(2L, execute(store, a, send(id = "next")).getJSONObject("receipt").getLong("sequence"))
        val before = raw(run)
        assertFalse(execute(store, b, list()).toString().contains("EVIDENCE"))
        assertEquals(before, raw(run))
        assertEquals("READ", execute(store, b, read()).getJSONObject("receipt").getString("status"))
        EtaDatabase.closeForTests(); store = AgentSubagentCheckpoint(context, run)
        assertEquals("READ", execute(store, a, list()).getJSONArray("messages").getJSONObject(0).getString("status"))
        assertEquals("EVIDENCE", execute(store, b, read()).getString("content"))
    }

    @Test fun checkpointRecoveryNeverInjectsInfoAndInterruptsUnrestorableInvocation() {
        val run = "crash"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); val b = member(store, run, "b")
        execute(store, a, send())
        val durable = raw(run)
        assertTrue(AgentSubagentCheckpoint.recover(context, run, emptyList(), finalize = false).isEmpty())
        assertEquals(durable, raw(run))
        val recovered = AgentSubagentCheckpoint.recover(context, run, emptyList())
        assertEquals(1, recovered.size)
        assertFalse(recovered.toString().contains("EVIDENCE"))
        assertTrue(recovered.single().content.contains("interrupted"))
        assertEquals(durable, raw(run))
        val reopened = AgentSubagentCheckpoint(context, run)
        assertEquals("MAILBOX_CALLER_TERMINAL", execute(reopened, b, read()).getString("code"))
        assertEquals("STORED", JSONArray(raw(run)).getJSONObject(0).getString("status"))
        assertEquals(3, JSONArray(raw(run, "runtime_subagent_tasks_v1")).length())
    }

    @Test fun persistedMembershipVerifiesParentGroupSessionAndExplicitPermission() {
        val run = "bound"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); member(store, run, "b", "another")
        val denied = member(store, run, "denied", allowed = false)
        assertEquals("MAILBOX_RECIPIENT_UNAUTHORIZED", execute(store, a, send()).getString("code"))
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, denied, list()).getString("code"))
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, a.copy(subagentId = "spoof"), list()).getString("code"))
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, a.copy(invocation = a.invocation.copy(
            scope = SubagentMailbox.Scope("wrong-parent", "batch"))), list()).getString("code"))
        assertEquals("MAILBOX_UNAUTHORIZED", execute(AgentSubagentCheckpoint(context, "other-parent"), a, list()).getString("code"))
        assertThrows(IllegalStateException::class.java) { store.bind(a, true) { true } }
        val noGroup = SubagentMailbox.Identity(SubagentMailbox.Invocation(SubagentMailbox.Scope(run, "batch"), "legacy"), "sub-legacy")
        store.started("legacy", "legacy-call", 2, "sub-legacy", "fixture", "accepted")
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, noGroup, list()).getString("code"))
    }

    @Test fun receiverCloseIsDurableBeforeGroupCompletionAndDuplicateRemainsStable() {
        val run = "terminal"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); val b = member(store, run, "b")
        val original = execute(store, a, send()).getJSONObject("receipt").toString()
        store.close(b)
        assertTrue(raw(run, "runtime_subagent_tasks_v1").contains("\"mailbox_closed\":true"))
        assertEquals("MAILBOX_RECIPIENT_TERMINAL", execute(store, a, send("later")).getString("code"))
        assertEquals(original, execute(store, a, send()).getJSONObject("receipt").toString())
        EtaDatabase.closeForTests()
        assertEquals("MAILBOX_RECIPIENT_TERMINAL", execute(AgentSubagentCheckpoint(context, run), a, send("later")).getString("code"))
    }

    @Test fun cancelledReceiverIsRejectedBeforeCheckpointTerminalWriteAndParentStopRejectsAll() {
        val run = "cancel"; var parentCancelled = false; var bActive = true
        val store = AgentSubagentCheckpoint(context, run) { parentCancelled }
        val a = member(store, run, "a"); member(store, run, "b", active = { bActive })
        bActive = false
        assertEquals("MAILBOX_RECIPIENT_TERMINAL", execute(store, a, send()).getString("code"))
        assertTrue(raw(run).isEmpty())
        parentCancelled = true
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, a, list()).getString("code"))
    }

    @Test fun corruptedStorageFailsExplicitlyWithoutReplacingOrAcknowledgingIt() {
        val run = "storage-failure"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); member(store, run, "b")
        runBlocking { EtaDatabase.get(context).runtimeRunDao().insertTextChunk(
            AgentTextChunkEntity(SubagentMailbox.TABLE, run, "messages", 0, "broken-json")) }
        assertEquals("MAILBOX_STORAGE_FAILED", execute(store, a, send()).getString("code"))
        assertEquals("broken-json", raw(run))
    }

    @Test fun failedDatabaseWriteRollsBackSendAndReadBeforeAnySuccessReceipt() {
        val run = "write-failure"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); val b = member(store, run, "b")
        assertTrue(execute(store, a, send()).getBoolean("ok"))
        val before = raw(run)
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER mailbox_fail_insert BEFORE INSERT ON agent_text_chunks " +
            "WHEN NEW.owner_table = 'runtime_subagent_mailbox_v1' BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END")
        try {
            val failedSend = execute(store, a, send("new"))
            assertEquals("MAILBOX_STORAGE_FAILED", failedSend.getString("code"))
            assertEquals(before, raw(run))
            val failedRead = execute(store, b, read())
            assertEquals("MAILBOX_STORAGE_FAILED", failedRead.getString("code"))
            assertFalse(failedRead.toString().contains("EVIDENCE"))
            assertEquals(before, raw(run))
        } finally { sql.execSQL("DROP TRIGGER mailbox_fail_insert") }
        assertEquals(2L, execute(store, a, send("new")).getJSONObject("receipt").getLong("sequence"))
        assertEquals("READ", execute(store, b, read()).getJSONObject("receipt").getString("status"))
    }

    @Test fun mailboxAndMembershipHaveSameAcknowledgementCleanupLifetime() {
        val run = "ack"; val store = AgentSubagentCheckpoint(context, run)
        val a = member(store, run, "a"); member(store, run, "b")
        execute(store, a, send())
        assertFalse(raw(run).isEmpty())
        runBlocking { EtaDatabase.get(context).runtimeRunDao().removeInFlightRun(run) }
        assertTrue(raw(run).isEmpty()); assertTrue(raw(run, "runtime_subagent_tasks_v1").isEmpty())
        assertEquals("MAILBOX_UNAUTHORIZED", execute(store, a, list()).getString("code"))
        val anotherRun = "remove-helper"; val another = AgentSubagentCheckpoint(context, anotherRun)
        val c = member(another, anotherRun, "a"); member(another, anotherRun, "b")
        execute(another, c, send()); AgentSubagentCheckpoint.remove(context, anotherRun)
        assertTrue(raw(anotherRun).isEmpty())
    }
}
