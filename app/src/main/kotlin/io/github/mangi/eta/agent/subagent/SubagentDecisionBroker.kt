package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One per live parent. Storage serializes the read/modify/write in one durable transaction.
 * No INFO messages, model polling, synthetic tool responses or background worker are involved. */
internal class SubagentDecisionBroker(
    private val runId: String,
    private val parent: AgentRunController,
    private val storage: Storage,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 5 * 60_000L,
) : AutoCloseable {
    interface Storage {
        fun accessRequests(operation: (JSONArray) -> JSONObject): JSONObject
    }
    private data class Waiter(val controller: AgentRunController, val ready: AtomicBoolean = AtomicBoolean(false))
    private val waiters = ConcurrentHashMap<String, Waiter>()
    @Volatile private var closed = false
    @Volatile private var undelivered = false
    private val lock = Any()

    fun ask(taskId: String, invocationId: String, call: AgentModelClient.ToolCall, child: AgentRunController): AgentModelClient.ToolResult {
        val args = try { JSONObject(call.argumentsJson) } catch (_: Exception) { return error("ASK_INVALID_ARGUMENTS") }
        if (args.keys().asSequence().any { it !in setOf("request_id", "question", "context_refs") }) return error("ASK_INVALID_ARGUMENTS")
        val requestId = args.opt("request_id") as? String ?: return error("ASK_INVALID_ARGUMENTS")
        val question = args.opt("question") as? String ?: return error("ASK_INVALID_ARGUMENTS")
        val refs = args.optJSONArray("context_refs") ?: if (args.has("context_refs")) return error("ASK_INVALID_ARGUMENTS") else JSONArray()
        // Keep even the worst escaped single record below the delivery budget.
        if (args.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024) return error("ASK_INVALID_ARGUMENTS")
        if (!ID.matches(requestId) || question.isBlank() || question.length > 4000 || refs.length() > 8 ||
            (0 until refs.length()).any { refs.opt(it) !is String || refs.optString(it).isBlank() || refs.optString(it).length > 512 } ||
            taskId.isBlank() || taskId.length > 256 || invocationId.isBlank() || invocationId.length > 256 ||
            call.id.isBlank() || call.id.length > 256 || runId.length > 256) return error("ASK_INVALID_ARGUMENTS")
        val waiter = Waiter(child)
        val stored = try {
            synchronized(lock) {
                if (closed || parent.isCancelled) return error("ASK_PARENT_UNAVAILABLE")
                if (child.isCancelled) return error("ASK_CHILD_CANCELLED")
                if (waiters.putIfAbsent(requestId, waiter) != null) return error("ASK_ALREADY_WAITING")
                val record = storage.accessRequests { records ->
                    val existing = find(records, requestId)
                    if (existing != null) {
                        if (existing.optString("task_id") != taskId || existing.optString("invocation_id") != invocationId ||
                            existing.optString("tool_call_id") != call.id || existing.optString("question") != question ||
                            existing.optJSONArray("context_refs")?.toString() != refs.toString()) problem("ASK_REQUEST_CONFLICT")
                        else JSONObject(existing.toString()).put("duplicate", true)
                    } else if (records.length() >= MAX_REQUESTS) problem("ASK_FULL") else {
                        JSONObject().put("ok", true).put("request_id", requestId).put("parent_run_id", runId)
                            .put("task_id", taskId).put("invocation_id", invocationId).put("tool_call_id", call.id)
                            .put("question", question).put("context_refs", JSONArray(refs.toString()))
                            .put("deadline", now() + timeoutMs).put("status", "pending").put("delivered", false)
                            .also { records.put(it) }
                    }
                }
                if (!record.optBoolean("ok") && !record.has("status")) { waiters.remove(requestId, waiter); return AgentModelClient.ToolResult(record.toString()) }
                if (record.optString("status") != "pending") {
                    waiters.remove(requestId, waiter)
                    return AgentModelClient.ToolResult(record.toString())
                }
                if (!record.optBoolean("delivered")) undelivered = true
                record
            }
        } catch (_: Exception) {
            waiters.remove(requestId, waiter)
            return error("ASK_STORAGE_UNKNOWN", "Request storage outcome is unknown; do not replay the tool automatically.")
        }
        parent.signalWorkAvailable()
        try {
            val remaining = (stored.optLong("deadline") - now()).coerceAtLeast(0)
            child.awaitWorkAvailableFor(remaining) { waiter.ready.get() || closed || child.isPaused }
            val terminal = when {
                child.hasPendingParentDirective || child.isPaused -> "interrupted"
                closed || parent.isCancelled -> "parent_unavailable"
                waiter.ready.get() -> null
                now() >= stored.optLong("deadline") -> "timed_out"
                else -> "interrupted" // a controller input woke the waiter without an answer
            }
            val result = finishWait(requestId, terminal)
            if (result.optString("status") in setOf("answer", "refuse", "revise", "cancel")) {
                // Keep a committed result even if cancellation races after this point.
                child.protectCommittedDecisionUntilToolBatch()
            }
            return AgentModelClient.ToolResult(result.toString())
        } catch (failure: java.util.concurrent.CancellationException) {
            return committedDecisionOrThrow(requestId, child, failure)
        } catch (failure: AgentRunCancelledException) {
            return committedDecisionOrThrow(requestId, child, failure)
        } catch (_: Exception) {
            return error("ASK_STORAGE_UNKNOWN", "Response or terminal state could not be confirmed; do not replay.")
        } finally { waiters.remove(requestId, waiter) }
    }

    /** Cancellation may wake after a response committed. Preserve that exact decision and
     * stop at the completed tool-batch boundary; cancellation still prevents later work. */
    private fun committedDecisionOrThrow(id: String, child: AgentRunController, failure: RuntimeException): AgentModelClient.ToolResult {
        val record = try { finishWait(id, "cancelled") } catch (storageFailure: Exception) {
            failure.addSuppressed(storageFailure)
            throw failure
        }
        if (record.optString("status") in setOf("answer", "refuse", "revise", "cancel")) {
            child.protectCommittedDecisionUntilToolBatch()
            child.requestStopAfterToolBatch()
            return AgentModelClient.ToolResult(record.toString())
        }
        throw failure
    }

    private fun finishWait(id: String, terminal: String?): JSONObject = synchronized(lock) {
        storage.accessRequests { records ->
            val record = find(records, id) ?: return@accessRequests problem("ASK_REQUEST_MISSING")
            if (terminal != null && record.optString("status") == "pending") record.put("status", terminal).put("ok", false)
            undelivered = (0 until records.length()).any { i -> records.getJSONObject(i).let {
                it.optString("status") == "pending" && !it.optBoolean("delivered") && now() < it.optLong("deadline") } }
            JSONObject(record.toString())
        }
    }

    fun respond(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = try { JSONObject(call.argumentsJson) } catch (_: Exception) { return error("ASK_INVALID_ARGUMENTS") }
        if (args.keys().asSequence().any { it !in setOf("request_id", "task_id", "invocation_id", "tool_call_id", "action", "response") }) return error("ASK_INVALID_ARGUMENTS")
        val id = args.opt("request_id") as? String ?: return error("ASK_INVALID_ARGUMENTS")
        val action = args.opt("action") as? String ?: return error("ASK_INVALID_ARGUMENTS")
        val text = args.opt("response") as? String ?: ""
        if (action !in setOf("answer", "refuse", "revise", "cancel") || text.length > 4000 ||
            (action != "cancel" && text.isBlank())) return error("ASK_INVALID_ARGUMENTS")
        var transitioned = false
        var wake: Waiter? = null
        val result = try {
            // Lock order matches controller pause propagation: parent -> child -> broker.
            // Commit and cancellation/revision therefore have a single first-terminal order.
            parent.withDecisionBoundary { parentState ->
                val waiter = waiters[id]
                if (waiter == null) {
                    synchronized(lock) {
                        storage.accessRequests { records ->
                            val record = find(records, id) ?: return@accessRequests problem("ASK_REQUEST_UNAVAILABLE")
                            if (!matches(record, args, runId)) return@accessRequests problem("ASK_OWNER_MISMATCH")
                            if (record.optString("status") == action && record.optString("response") == text)
                                JSONObject().put("ok", true).put("duplicate", true)
                            else problem("ASK_REQUEST_TERMINAL")
                        }
                    }
                } else waiter.controller.withDecisionBoundary { childState ->
                    synchronized(lock) {
                        val committed = storage.accessRequests { records ->
                            val record = find(records, id) ?: return@accessRequests problem("ASK_REQUEST_UNAVAILABLE")
                            if (!matches(record, args, runId)) return@accessRequests problem("ASK_OWNER_MISMATCH")
                            if (record.optString("status") != "pending") {
                                if (record.optString("status") == action && record.optString("response") == text)
                                    return@accessRequests JSONObject().put("ok", true).put("duplicate", true)
                                return@accessRequests problem("ASK_REQUEST_TERMINAL")
                            }
                            if (now() >= record.optLong("deadline")) {
                                record.put("status", "timed_out").put("ok", false)
                                transitioned = true
                                return@accessRequests problem("ASK_REQUEST_EXPIRED")
                            }
                            if (closed || parentState != null || childState != null || waiters[id] !== waiter) {
                                val state = when {
                                    closed || parentState != null -> "parent_unavailable"
                                    childState == "cancelled" -> "cancelled"
                                    else -> "interrupted"
                                }
                                record.put("status", state).put("ok", false)
                                transitioned = true
                                return@accessRequests problem("ASK_REQUEST_TERMINAL")
                            }
                            record.put("status", action).put("response", text)
                            transitioned = true
                            JSONObject().put("ok", true).put("duplicate", false)
                        }
                        if (transitioned || committed.optBoolean("ok")) {
                            waiter.ready.set(true)
                            wake = waiter
                        }
                        committed
                    }
                }
            }
        } catch (_: Exception) { return error("ASK_STORAGE_UNKNOWN", "Response outcome unknown; inspect before retrying the same request.") }
        wake?.controller?.signalWorkAvailable()
        return AgentModelClient.ToolResult(result.toString())
    }

    fun hasUndelivered(): Boolean = undelivered
    fun pendingBatch(): List<JSONObject> = synchronized(lock) {
        val result = mutableListOf<JSONObject>()
        storage.accessRequests { records ->
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                if (r.optString("status") == "pending" && !r.optBoolean("delivered") && now() < r.optLong("deadline")) {
                    val copy = JSONObject(r.toString())
                    val size = copy.toString().toByteArray(Charsets.UTF_8).size
                    val current = result.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }
                    if (result.size >= MAX_BATCH || current + size > MAX_BATCH_BYTES) break
                    result.add(copy)
                }
            }
            JSONObject().put("ok", true)
        }
        if (result.isEmpty()) undelivered = false
        result
    }
    fun delivered(ids: Collection<String>) = synchronized(lock) {
        storage.accessRequests { records ->
            ids.forEach { id -> find(records, id)?.put("delivered", true) }
            undelivered = (0 until records.length()).any { i -> records.getJSONObject(i).let {
                it.optString("status") == "pending" && !it.optBoolean("delivered") && now() < it.optLong("deadline") } }
            JSONObject().put("ok", true)
        }
        Unit
    }
    override fun close() {
        synchronized(lock) { closed = true }
        waiters.values.forEach { it.controller.signalWorkAvailable() }
    }
    companion object {
        const val ASK = "ask_parent"
        const val RESPOND = "subagent_respond"
        const val TABLE = "runtime_subagent_decisions_v1"
        private const val MAX_REQUESTS = 128
        private const val MAX_BATCH = 8
        private const val MAX_BATCH_BYTES = 64 * 1024
        private fun matches(record: JSONObject, args: JSONObject, runId: String): Boolean =
            record.optString("parent_run_id") == runId && record.optString("task_id") == args.optString("task_id") &&
                record.optString("invocation_id") == args.optString("invocation_id") && record.optString("tool_call_id") == args.optString("tool_call_id")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private fun find(records: JSONArray, id: String): JSONObject? =
            (0 until records.length()).map(records::getJSONObject).firstOrNull { it.optString("request_id") == id }
        private fun problem(code: String) = JSONObject().put("ok", false).put("code", code)
        private fun error(code: String, message: String = "No decision was applied.") = AgentModelClient.ToolResult(problem(code).put("message", message).toString())
        fun askSchema(): JSONObject = AgentToolSchema.function(ASK,
            "Child-only: request a parent decision solely for a critical blocker. Persists and waits with a deadline. Never use for routine progress. Parent decisions are internal, not user authorization.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("request_id", JSONObject().put("type", "string").put("maxLength", 128))
                .put("question", JSONObject().put("type", "string").put("maxLength", 4000))
                .put("context_refs", JSONObject().put("type", "array").put("maxItems", 8).put("items", JSONObject().put("type", "string").put("maxLength", 512))))
                .put("required", JSONArray(listOf("request_id", "question"))).put("additionalProperties", false))
        fun responseSchema(): JSONObject = AgentToolSchema.function(RESPOND,
            "Answer a pending critical child request. Copy request_id, task_id, invocation_id and tool_call_id exactly from the batched request; reply answer/refuse/revise/cancel. A decision does not add user authorization.",
            JSONObject().put("type", "object").put("properties", JSONObject().also { properties ->
                listOf("request_id", "task_id", "invocation_id", "tool_call_id", "response").forEach { properties.put(it, JSONObject().put("type", "string")) }
                properties.put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("answer", "refuse", "revise", "cancel"))))
            }).put("required", JSONArray(listOf("request_id", "task_id", "invocation_id", "tool_call_id", "action"))).put("additionalProperties", false))
    }
}
