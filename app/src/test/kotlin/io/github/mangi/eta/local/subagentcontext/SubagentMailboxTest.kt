package io.github.mangi.eta.local.subagentcontext

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.subagent.SubagentProfile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentMailboxTest {
    private class Fixture {
        val store = MailboxMemoryStorage()
        val a = store.add("a")
        val b = store.add("b")
        fun invoke(who: SubagentMailbox.Identity = a, args: JSONObject, cancel: () -> Unit = {}): JSONObject =
            JSONObject(SubagentMailbox(who, store, cancel).execute(AgentModelClient.ToolCall("call", "agent_mailbox", args.toString())).content)
        fun send(id: String = "message", content: String = "EVIDENCE", receiver: String = "b") =
            JSONObject().put("action", "send").put("receiver", receiver).put("message_id", id).put("content", content)
        fun list() = JSONObject().put("action", "list")
        fun read() = JSONObject().put("action", "read").put("message_id", "message")
    }

    @Test fun discoveryAndListExposeOnlyReceiptAndReadIsReceiverInitiated() {
        val f = Fixture()
        val stored = f.invoke(args = f.send())
        assertEquals("STORED", stored.getJSONObject("receipt").getString("status"))
        val listing = f.invoke(f.b, f.list())
        assertEquals(2, listing.getJSONArray("members").length())
        assertEquals("b", listing.getJSONObject("self").getString("task_id"))
        assertFalse(listing.toString().contains("EVIDENCE"))
        assertEquals("STORED", listing.getJSONArray("messages").getJSONObject(0).getString("status"))
        assertEquals("MAILBOX_MESSAGE_UNAVAILABLE", f.invoke(args = f.read()).getString("code"))
        val read = f.invoke(f.b, f.read())
        assertEquals("EVIDENCE", read.getString("content"))
        assertEquals("READ", read.getJSONObject("receipt").getString("status"))
        assertEquals("not_asserted", read.getString("adoption"))
        assertEquals("READ", f.invoke(args = f.list()).getJSONArray("messages").getJSONObject(0).getString("status"))
        assertEquals(read.toString(), f.invoke(f.b, f.read()).toString())
    }

    @Test fun exactDuplicateSurvivesReceiverTerminationButConflictingPayloadDoesNot() {
        val f = Fixture()
        val first = f.invoke(args = f.send()).getJSONObject("receipt").toString()
        f.store.close(f.b)
        val duplicate = f.invoke(args = f.send())
        assertTrue(duplicate.getBoolean("duplicate"))
        assertEquals(first, duplicate.getJSONObject("receipt").toString())
        assertEquals("MAILBOX_MESSAGE_CONFLICT", f.invoke(args = f.send(content = "CHANGED")).getString("code"))
        assertEquals("MAILBOX_MESSAGE_CONFLICT", f.invoke(args = f.send(receiver = "c")).getString("code"))
        assertEquals("MAILBOX_RECIPIENT_TERMINAL", f.invoke(args = f.send(id = "new")).getString("code"))
        assertEquals(1, f.store.messages.length())
    }

    @Test fun wrongParentGroupCallerAndSpoofedModelFieldsAreRejected() {
        val f = Fixture()
        for (identity in listOf(f.a.copy(subagentId = "spoof"), f.a.copy(invocation = f.a.invocation.copy(taskId = "absent")),
            f.a.copy(invocation = f.a.invocation.copy(scope = SubagentMailbox.Scope("other-parent", "batch"))),
            f.a.copy(invocation = f.a.invocation.copy(scope = SubagentMailbox.Scope("parent", "other-group"))))) {
            assertEquals("MAILBOX_UNAUTHORIZED", f.invoke(identity, f.send()).getString("code"))
        }
        for (field in listOf("sender", "parent_run_id", "group", "task_id", "group_key", "caller")) {
            assertEquals("MAILBOX_INVALID_ARGUMENTS", f.invoke(args = f.send().put(field, "spoof")).getString("code"))
        }
        assertEquals(0, f.store.messages.length())
    }

    @Test fun otherGroupsUnauthorizedProfilesSelfAndUnknownReceiverAreRejected() {
        val f = Fixture()
        f.store.add("other", "other-group"); f.store.add("denied", allowed = false)
        for (receiver in listOf("other", "denied", "a", "absent"))
            assertEquals("MAILBOX_RECIPIENT_UNAUTHORIZED", f.invoke(args = f.send(receiver = receiver)).getString("code"))
        assertEquals("MAILBOX_UNAUTHORIZED", f.invoke(f.store.add("caller-denied", allowed = false), f.list()).getString("code"))
        assertFalse(f.invoke(args = f.list()).toString().contains("other-group"))
    }

    @Test fun onlyInfoAndBoundedTypedParametersAreAccepted() {
        val f = Fixture()
        for (args in listOf(f.send().put("kind", "REVISE"), f.send().put("content", JSONObject.NULL), f.send(content = " "),
            f.send(content = "x".repeat(SubagentMailbox.MAX_CONTENT + 1)), f.send(id = "bad id"),
            f.list().put("limit", 33), f.list().put("after_sequence", -1), f.list().put("after_sequence", "0"),
            f.list().put("direction", "all-groups"), JSONObject().put("action", "wait"))) {
            assertEquals("MAILBOX_INVALID_ARGUMENTS", f.invoke(args = args).getString("code"))
        }
        assertEquals(0, f.store.messages.length())
    }

    @Test fun monotonicSequenceAndPaginationStayStableWithConcurrentSenders() {
        val f = Fixture(); val c = f.store.add("c"); val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..12).map { id -> pool.submit(Callable { f.invoke(if (id % 2 == 0) f.a else c, f.send(id = "m$id")) }) }
            assertTrue(jobs.all { it.get(3, TimeUnit.SECONDS).getBoolean("ok") })
            val first = f.invoke(f.b, f.list().put("direction", "inbox").put("limit", 5))
            assertTrue(first.getBoolean("has_more"))
            assertEquals(5L, first.getLong("next_after_sequence"))
            val rest = f.invoke(f.b, f.list().put("after_sequence", 5))
            assertFalse(rest.getBoolean("has_more"))
            assertEquals((6L..12L).toList(), (0 until rest.getJSONArray("messages").length()).map {
                rest.getJSONArray("messages").getJSONObject(it).getLong("sequence") })
        } finally { pool.shutdownNow() }
    }

    @Test fun readStatusAndOutboxFilteringNeverExposeAnotherMembersContent() {
        val f = Fixture()
        f.invoke(args = f.send()); f.invoke(f.b, f.read())
        assertEquals("READ", f.invoke(args = f.send()).getJSONObject("receipt").getString("status"))
        assertEquals(0, f.invoke(args = f.list().put("direction", "inbox")).getJSONArray("messages").length())
        assertEquals(0, f.invoke(f.b, f.list().put("direction", "outbox")).getJSONArray("messages").length())
        val c = f.store.add("c")
        assertEquals(0, f.invoke(c, f.list()).getJSONArray("messages").length())
        assertEquals("MAILBOX_MESSAGE_UNAVAILABLE", f.invoke(c, f.read()).getString("code"))
    }

    @Test fun storageFailureAndLostAcknowledgementRequireExactRetryNotNewEffects() {
        val f = Fixture(); f.store.failBeforeCommit = true
        assertEquals("MAILBOX_STORAGE_FAILED", f.invoke(args = f.send()).getString("code"))
        assertEquals(0, f.store.messages.length())
        f.store.failBeforeCommit = false; f.store.failAfterCommit = true
        assertEquals("MAILBOX_STORAGE_FAILED", f.invoke(args = f.send()).getString("code"))
        assertEquals(1, f.store.messages.length())
        f.store.failAfterCommit = false
        assertTrue(f.invoke(args = f.send()).getBoolean("duplicate"))
        assertEquals(1L, f.invoke(args = f.send()).getJSONObject("receipt").getLong("sequence"))
    }

    @Test fun cancellationAndTerminalCallerDoNotMutateMessages() {
        val f = Fixture()
        assertThrows(AgentRunCancelledException::class.java) {
            f.invoke(args = f.send(), cancel = { throw AgentRunCancelledException() })
        }
        f.store.close(f.a)
        assertEquals("MAILBOX_CALLER_TERMINAL", f.invoke(args = f.send()).getString("code"))
        assertEquals(0, f.store.messages.length())
    }

    @Test fun failedReadDoesNotReturnContentOrFalselyMarkRead() {
        val f = Fixture(); f.invoke(args = f.send()); f.store.failBeforeCommit = true
        val failed = f.invoke(f.b, f.read())
        assertEquals("MAILBOX_STORAGE_FAILED", failed.getString("code")); assertFalse(failed.toString().contains("EVIDENCE"))
        assertEquals("STORED", f.store.messages.getJSONObject(0).getString("status"))
        f.store.failBeforeCommit = false; f.store.failAfterCommit = true
        val lost = f.invoke(f.b, f.read())
        assertEquals("MAILBOX_STORAGE_FAILED", lost.getString("code")); assertFalse(lost.toString().contains("EVIDENCE"))
        // READ records an attempted content read, not receipt by a model or adoption.
        assertEquals("READ", f.store.messages.getJSONObject(0).getString("status"))
        f.store.failAfterCommit = false
        assertEquals("EVIDENCE", f.invoke(f.b, f.read()).getString("content"))
    }

    @Test fun fullMailboxDoesNotLosePriorIdempotencyOrAllocateAnUncommittedSequence() {
        val f = Fixture()
        for (id in 1..SubagentMailbox.MAX_MESSAGES) assertTrue(f.invoke(args = f.send(id = "m$id")).getBoolean("ok"))
        assertEquals("MAILBOX_FULL", f.invoke(args = f.send(id = "overflow")).getString("code"))
        assertTrue(f.invoke(args = f.send(id = "m1")).getBoolean("duplicate"))
        assertEquals(SubagentMailbox.MAX_MESSAGES, f.store.messages.length())
    }

    @Test fun cancelledReceiverCannotReceiveEvenWhileDurableTaskStillLooksRunning() {
        val f = Fixture(); var running = true
        f.store.add("c", isActive = { running }); running = false
        assertEquals("running", f.store.members.getValue("c").state)
        assertEquals("MAILBOX_RECIPIENT_TERMINAL", f.invoke(args = f.send(receiver = "c")).getString("code"))
        f.store.interrupted()
        assertEquals("MAILBOX_UNAUTHORIZED", f.invoke(args = f.list()).getString("code"))
    }

    @Test fun builtinDefaultsAllowMailboxButExplicitAllowlistAndDenyWin() {
        for (profile in SubagentProfile.BUILTIN_PROFILES) {
            assertTrue(profile.isToolAllowed("agent_mailbox"))
            assertFalse(profile.copy(explicitTools = setOf("read_file")).isToolAllowed("agent_mailbox"))
            assertTrue(profile.copy(explicitTools = setOf("agent_mailbox")).isToolAllowed("agent_mailbox"))
            assertFalse(profile.copy(explicitTools = setOf("*"), disallowedTools = setOf("agent_mailbox")).isToolAllowed("agent_mailbox"))
            assertFalse(profile.isToolAllowed("conversation_send"))
        }
    }

    @Test fun newInvocationDoesNotInheritUnreadInboxOrSameIdRetryAuthority() {
        val f = Fixture(); f.invoke(args = f.send()); f.store.close(f.b)
        val resumed = f.store.add("b-resumed")
        assertEquals(0, f.invoke(resumed, f.list()).getJSONArray("messages").length())
        assertEquals("MAILBOX_MESSAGE_UNAVAILABLE", f.invoke(resumed, f.read()).getString("code"))
        assertEquals("MAILBOX_MESSAGE_CONFLICT", f.invoke(resumed, f.send()).getString("code"))
    }
}
