package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentDecisionBrokerTest {
    internal class Memory : SubagentDecisionBroker.Storage {
        var records = JSONArray()
        @Volatile var fail = false
        @Volatile var afterCommit: (() -> Unit)? = null
        override fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject = synchronized(this) {
            if (fail) error("injected storage failure")
            val copy = JSONArray(records.toString())
            val result = operation(copy)
            records = copy
            afterCommit?.invoke()
            result
        }
    }
    private fun ask(id: String) = AgentModelClient.ToolCall("call-$id", "ask_parent",
        JSONObject().put("request_id", id).put("question", "critical $id").put("context_refs", JSONArray(listOf("evidence"))).toString())
    private fun respond(record: JSONObject, action: String = "answer", text: String = "decision") = AgentModelClient.ToolCall("response", "subagent_respond",
        JSONObject().put("request_id", record.getString("request_id")).put("task_id", record.getString("task_id"))
            .put("invocation_id", record.getString("invocation_id")).put("tool_call_id", record.getString("tool_call_id"))
            .put("action", action).put("response", text).toString())
    private fun awaitBatch(broker: SubagentDecisionBroker, count: Int): List<JSONObject> {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < end) {
            val batch = broker.pendingBatch()
            if (batch.size == count) return batch
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $count requests")
    }
    @Test fun simultaneousBatchDuplicateMismatchAndLate() {
        val store = Memory(); val broker = SubagentDecisionBroker("run", AgentRunController(), store, timeoutMs = 3000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf("a", "b").map { id -> pool.submit(Callable { JSONObject(broker.ask("task-$id", "session-$id", ask(id), AgentRunController()).content) }) }
            val batch = awaitBatch(broker, 2)
            broker.delivered(batch.map { it.getString("request_id") })
            assertTrue(broker.pendingBatch().isEmpty())
            val a = batch.single { it.getString("request_id") == "a" }; val b = batch.single { it.getString("request_id") == "b" }
            val bad = respond(a).copy(argumentsJson = JSONObject(respond(a).argumentsJson).put("tool_call_id", "foreign").toString())
            assertEquals("ASK_OWNER_MISMATCH", JSONObject(broker.respond(bad).content).getString("code"))
            assertTrue(JSONObject(broker.respond(respond(a)).content).getBoolean("ok"))
            assertTrue(JSONObject(broker.respond(respond(a)).content).getBoolean("duplicate"))
            assertEquals("ASK_REQUEST_TERMINAL", JSONObject(broker.respond(respond(a, text = "changed")).content).getString("code"))
            assertTrue(JSONObject(broker.respond(respond(b, "refuse", "no")).content).getBoolean("ok"))
            assertEquals("answer", jobs[0].get(3, TimeUnit.SECONDS).getString("status"))
            assertEquals("refuse", jobs[1].get(3, TimeUnit.SECONDS).getString("status"))
        } finally { broker.close(); pool.shutdownNow() }
    }
    @Test fun timeoutPauseRevisionEndAndStorageFailureAreBounded() {
        val store = Memory(); val parent = AgentRunController(); val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 500)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val timeout = pool.submit(Callable { JSONObject(broker.ask("task", "session", ask("timeout"), AgentRunController()).content) })
            val first = awaitBatch(broker, 1).single()
            assertEquals("timed_out", timeout.get(2, TimeUnit.SECONDS).getString("status"))
            assertEquals("ASK_REQUEST_TERMINAL", JSONObject(broker.respond(respond(first)).content).getString("code"))
            val child = AgentRunController()
            val revision = pool.submit(Callable { JSONObject(broker.ask("task", "session", ask("revision"), child).content) })
            awaitBatch(broker, 1); assertTrue(child.steerFromParent("new direction"))
            assertEquals("interrupted", revision.get(2, TimeUnit.SECONDS).getString("status"))
            val paused = AgentRunController()
            val wait = pool.submit(Callable { JSONObject(broker.ask("task", "session", ask("pause"), paused).content) })
            awaitBatch(broker, 1); paused.pause()
            assertEquals("interrupted", wait.get(2, TimeUnit.SECONDS).getString("status"))
            broker.close()
            assertEquals("ASK_PARENT_UNAVAILABLE", JSONObject(broker.ask("task", "session", ask("closed"), AgentRunController()).content).getString("code"))
            val failing = SubagentDecisionBroker("run", parent, store); store.fail = true
            assertEquals("ASK_STORAGE_UNKNOWN", JSONObject(failing.ask("task", "session", ask("failure"), AgentRunController()).content).getString("code"))
        } finally { broker.close(); pool.shutdownNow() }
    }
    @Test fun cancellationAndExplicitRevisionAreNotReplayed() {
        val store = Memory(); val parent = AgentRunController(); val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 3000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val child = AgentRunController()
            val pending = pool.submit(Callable { broker.ask("task", "session", ask("cancelled"), child) })
            val first = awaitBatch(broker, 1).single()
            child.cancel()
            try { pending.get(3, TimeUnit.SECONDS); fail("cancelled wait must throw") }
            catch (failure: java.util.concurrent.ExecutionException) {
                assertTrue(failure.cause is io.github.mangi.eta.agent.runtime.AgentRunCancelledException)
            }
            assertEquals("ASK_REQUEST_TERMINAL", JSONObject(broker.respond(respond(first)).content).getString("code"))
            val revision = pool.submit(Callable { JSONObject(broker.ask("task", "session", ask("explicit"), AgentRunController()).content) })
            val second = awaitBatch(broker, 1).single()
            assertTrue(JSONObject(broker.respond(respond(second, "revise", "new objective")).content).getBoolean("ok"))
            val result = revision.get(3, TimeUnit.SECONDS)
            assertEquals("revise", result.getString("status")); assertEquals("new objective", result.getString("response"))
            assertEquals("call-explicit", result.getString("tool_call_id"))
        } finally { broker.close(); pool.shutdownNow() }
    }

    @Test fun committedAnswerWinsLaterPauseAndEarlierPauseRejects() {
        val pool = Executors.newFixedThreadPool(2)
        val store = Memory(); val parent = AgentRunController(); val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 5000)
        try {
            val firstChild = AgentRunController()
            val first = pool.submit(Callable { JSONObject(broker.ask("first", "one", ask("first"), firstChild).content) })
            val a = awaitBatch(broker, 1).single(); broker.delivered(listOf("first"))
            assertTrue(JSONObject(broker.respond(respond(a)).content).getBoolean("ok"))
            firstChild.pause()
            assertEquals("answer", first.get(3, TimeUnit.SECONDS).getString("status"))
            val secondChild = AgentRunController()
            val second = pool.submit(Callable { JSONObject(broker.ask("second", "two", ask("second"), secondChild).content) })
            val b = awaitBatch(broker, 1).single(); secondChild.pause()
            assertEquals("ASK_REQUEST_TERMINAL", JSONObject(broker.respond(respond(b)).content).getString("code"))
            assertEquals("interrupted", second.get(3, TimeUnit.SECONDS).getString("status"))
        } finally { broker.close(); pool.shutdownNow() }
    }

    @Test fun deliveryIsBoundedAndRemainingRequestsStayReady() {
        val pool = Executors.newFixedThreadPool(10); val store = Memory()
        val broker = SubagentDecisionBroker("run", AgentRunController(), store, timeoutMs = 5000)
        try {
            val jobs = (0 until 10).map { i -> pool.submit(Callable { broker.ask("task$i", "inv$i", ask("r$i"), AgentRunController()) }) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (synchronized(store) { store.records.length() } < 10 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(10, synchronized(store) { store.records.length() })
            val first = broker.pendingBatch(); assertEquals(8, first.size)
            broker.delivered(first.map { it.getString("request_id") })
            assertTrue(broker.hasUndelivered())
            val rest = broker.pendingBatch(); assertEquals(2, rest.size)
            (first + rest).forEach { assertTrue(JSONObject(broker.respond(respond(it)).content).getBoolean("ok")) }
            jobs.forEach { assertEquals("answer", JSONObject(it.get(3, TimeUnit.SECONDS).content).getString("status")) }
        } finally { broker.close(); pool.shutdownNow() }
    }

    @Test fun parentSteeringBeforeResponseWakesWaitingChildWithDurableTerminal() {
        val store = Memory(); val parent = AgentRunController()
        val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 60_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { JSONObject(broker.ask("task", "inv", ask("steer"), AgentRunController()).content) })
            val record = awaitBatch(broker, 1).single()
            assertTrue(parent.steer("new direction"))
            val reply = JSONObject(broker.respond(respond(record)).content)
            assertFalse(reply.getBoolean("ok")); assertEquals("ASK_REQUEST_TERMINAL", reply.getString("code"))
            assertEquals("parent_unavailable", pending.get(2, TimeUnit.SECONDS).getString("status"))
        } finally { broker.close(); pool.shutdownNow() }
    }

    @Test fun committedResponseThenCancelledChildRetainsExactDecisionAndRequestsBoundaryStop() {
        val store = Memory(); val parent = AgentRunController(); val child = AgentRunController()
        val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 60_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { JSONObject(broker.ask("task", "inv", ask("wins"), child).content) })
            val record = awaitBatch(broker, 1).single()
            // respond holds child lock until the durable response commit has returned.
            // Cancel reentrantly after commit, before that lock is released or the child can wake.
            store.afterCommit = {
                if (store.records.getJSONObject(0).optString("status") == "answer") {
                    store.afterCommit = null
                    child.cancel()
                }
            }
            val reply = JSONObject(broker.respond(respond(record)).content)
            store.afterCommit = null
            assertTrue(reply.getBoolean("ok"))
            val answer = pending.get(2, TimeUnit.SECONDS)
            assertEquals("answer", answer.getString("status")); assertEquals("decision", answer.getString("response"))
            assertEquals("call-wins", answer.getString("tool_call_id"))
            assertTrue(child.isCancelled); assertTrue(child.shouldStopAfterToolBatch())
        } finally { store.afterCommit = null; broker.close(); pool.shutdownNow() }
    }

    @Test fun cancelledChildBeforeResponseRejectsAndNeverCreatesAnAnswer() {
        val store = Memory(); val parent = AgentRunController(); val child = AgentRunController()
        val broker = SubagentDecisionBroker("run", parent, store, timeoutMs = 60_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { broker.ask("task", "inv", ask("loses"), child) })
            val record = awaitBatch(broker, 1).single()
            child.cancel()
            val reply = JSONObject(broker.respond(respond(record)).content)
            assertFalse(reply.getBoolean("ok"))
            try { pending.get(2, TimeUnit.SECONDS); fail("cancel must propagate") }
            catch (failure: java.util.concurrent.ExecutionException) {
                assertTrue(failure.cause is io.github.mangi.eta.agent.runtime.AgentRunCancelledException)
            }
            assertEquals("cancelled", synchronized(store) { store.records.getJSONObject(0).getString("status") })
        } finally { broker.close(); pool.shutdownNow() }
    }

}
