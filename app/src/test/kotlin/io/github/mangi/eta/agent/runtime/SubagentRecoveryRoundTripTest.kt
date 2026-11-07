package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.subagent.SubagentDecisionBroker
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.local.subagentcontext.SubagentMailbox
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentRecoveryRoundTripTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    private fun raw(table: String, field: String) = runBlocking {
        EtaDatabase.get(context).runtimeRunDao().textChunks(table, "run", field, Int.MAX_VALUE, 0).joinToString("") { it.content }
    }
    private fun member(store: AgentSubagentCheckpoint, task: String): SubagentMailbox.Identity {
        store.reserveGroupMember(task, "launch-$task", 1, "fixture", "group")
        val id = SubagentMailbox.Identity(SubagentMailbox.Invocation(SubagentMailbox.Scope("run", "group"), task), "sub-$task")
        store.bind(id, true) { true }
        store.started(task, "launch-$task", 1, id.subagentId, "fixture", "accepted-$task")
        return id
    }
    private fun mailbox(store: AgentSubagentCheckpoint, id: SubagentMailbox.Identity, args: JSONObject) =
        JSONObject(SubagentMailbox(id, store) {}.execute(AgentModelClient.ToolCall("mail", "agent_mailbox", args.toString())).content)
    private fun response(invocation: String) = AgentModelClient.ToolCall("response", "subagent_respond", JSONObject()
        .put("request_id", "request").put("task_id", "b").put("invocation_id", invocation)
        .put("tool_call_id", "ask-call").put("action", "answer").put("response", "old answer").toString())

    @Test fun previewThenAbsentOwnerRecoveryInterruptsPendingDecisionAndPreservesUnreadInfo() {
        val store = AgentSubagentCheckpoint(context, "run")
        val a = member(store, "a"); val b = member(store, "b")
        assertEquals("STORED", mailbox(store, a, JSONObject().put("action", "send").put("receiver", "b")
            .put("message_id", "info").put("content", "PRIVATE_INFO")).getJSONObject("receipt").getString("status"))
        val committed = CountDownLatch(1)
        val wrapped = object : SubagentDecisionBroker.Storage {
            override fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject {
                // The fixture keeps a waiter only to observe the lost-owner terminal state.
                // Rebind its storage handle on reopen; the old Room instance is really closed.
                val result = AgentSubagentCheckpoint(context, "run").accessRequests(operation)
                if (result.optString("status") == "pending") committed.countDown()
                return result
            }
        }
        val broker = SubagentDecisionBroker("run", AgentRunController(), wrapped, timeoutMs = 30_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(Callable { broker.ask("b", "old-invocation", AgentModelClient.ToolCall("ask-call", "ask_parent",
                JSONObject().put("request_id", "request").put("question", "blocking decision").toString()), AgentRunController()) })
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            val before = raw(SubagentDecisionBroker.TABLE, "requests")
            val inbox = raw(SubagentMailbox.TABLE, "messages")
            assertTrue(AgentSubagentCheckpoint.recover(context, "run", emptyList(), finalize = false).isEmpty())
            assertEquals(before, raw(SubagentDecisionBroker.TABLE, "requests"))
            assertFalse(future.isDone)
            // From here model an absent owner: do not send a reply or restart either child.
            EtaDatabase.closeForTests()
            val recovered = AgentSubagentCheckpoint.recover(context, "run", emptyList(), finalize = true)
            assertEquals(1, recovered.size)
            assertEquals(SubagentTaskGroup.messageId("run", "group"), recovered.single().messageId)
            assertTrue(recovered.single().content.contains("engine_status: interrupted"))
            assertFalse(recovered.toString().contains("PRIVATE_INFO"))
            assertEquals(inbox, raw(SubagentMailbox.TABLE, "messages"))
            val decision = JSONArray(raw(SubagentDecisionBroker.TABLE, "requests")).getJSONObject(0)
            assertEquals("interrupted", decision.getString("status"))
            assertEquals("request", decision.getString("request_id")); assertEquals("ask-call", decision.getString("tool_call_id"))
            assertEquals("old-invocation", decision.getString("invocation_id"))
            assertEquals("ASK_OWNER_MISMATCH", JSONObject(broker.respond(response("new-invocation")).content).getString("code"))
            assertEquals("ASK_REQUEST_TERMINAL", JSONObject(broker.respond(response("old-invocation")).content).getString("code"))
            assertEquals(recovered, AgentSubagentCheckpoint.recover(context, "run", recovered))
            assertEquals("MAILBOX_CALLER_TERMINAL", mailbox(AgentSubagentCheckpoint(context, "run"), b,
                JSONObject().put("action", "read").put("message_id", "info")).getString("code"))
            assertEquals("STORED", JSONArray(raw(SubagentMailbox.TABLE, "messages")).getJSONObject(0).getString("status"))
            broker.close()
            assertEquals("interrupted", JSONObject(future.get(5, TimeUnit.SECONDS).content).getString("status"))
            AgentSubagentCheckpoint.remove(context, "run")
            assertTrue(raw(SubagentMailbox.TABLE, "messages").isEmpty())
            assertTrue(raw(SubagentDecisionBroker.TABLE, "requests").isEmpty())
        } finally { broker.close(); pool.shutdownNow() }
    }

    @Test fun executedUnknownAndCommittedSideEffectsAreNotReplayedAndCallIdsAreBatchScoped() {
        val executions = AtomicInteger()
        fun effect(): String { executions.incrementAndGet(); return "committed result" }
        val done = effect()
        effect() // Executed, but its return is deliberately not committed to the transcript.
        val calls = """[{"id":"done","type":"function","function":{"name":"write","arguments":"{}"}},{"id":"unknown","type":"function","function":{"name":"write","arguments":"{}"}},{"id":"reused","type":"function","function":{"name":"subagent","arguments":"{}"}}]"""
        val transcript = listOf(AgentModelClient.ConversationMessage(role = "assistant", messageId = "assistant-run-1", toolCallsJson = calls),
            AgentModelClient.ConversationMessage(role = "tool", toolCallId = "done", content = done))
        val store = AgentSubagentCheckpoint(context, "run")
        store.reserveGroupMember("child", "reused", 2, "fixture", "group")
        store.started("child", "reused", 2, "sub-child", "fixture", "accepted-from-round-two")
        val recovered = AgentSubagentCheckpoint.recover(context, "run", transcript)
        assertEquals(done, recovered.single { it.toolCallId == "done" }.content)
        assertTrue(recovered.single { it.toolCallId == "unknown" }.content.contains("TOOL_INTERRUPTED"))
        assertTrue(recovered.single { it.toolCallId == "reused" }.content.contains("TOOL_INTERRUPTED"))
        assertFalse(recovered.toString().contains("accepted-from-round-two"))
        assertEquals(SubagentTaskGroup.messageId("run", "group"), recovered.last().messageId)
        assertEquals(recovered, AgentSubagentCheckpoint.recover(context, "run", recovered))
        assertEquals(2, executions.get())
    }
}
