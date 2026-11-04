package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunCoordination
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Run-owned tasks and ordered mailboxes. Threads, cancellation and parent ownership end together. */
internal class SubagentTaskManager(private val parent: AgentRunController) : AutoCloseable {
    internal data class Budget(val seconds: Long = 900, val rounds: Int = 100, val tokens: Long = 300_000) {
        companion object {
            fun from(args: JSONObject) = Budget(args.optLong("max_time_seconds", 900).coerceIn(1, 7200),
                args.optInt("max_rounds", 100).coerceIn(1, 1000), args.optLong("max_tokens", 300_000).coerceIn(1, 10_000_000))
        }
    }
    private data class Mail(val id: String = UUID.randomUUID().toString(), val text: String,
        val revision: Int, val important: Boolean = false, var delivery: String = "queued")
    internal inner class Task(val subagentId: String, val invocationId: String, val description: String, val budget: Budget) {
        val id: String = "task_${UUID.randomUUID()}"
        val controller = AgentRunController()
        @Volatile var onState: (String, String) -> Unit = { _, _ -> }
        var status = "queued"
        var revision = 1
        var appliedRevision = 1
        var requestRevision = 1
        var result: AgentModelClient.ToolResult? = null
        var finalized = false
        var pauseReason: String? = null
        var grantStarted = System.nanoTime()
        var rounds = 0
        var tokens = 0L
        var totalRounds = 0
        var totalTokens = 0L
        private val guidance = mutableListOf<Mail>()
        var latestReport = ""
        private var reportReminderSent = false
        val coordination = object : AgentRunCoordination {
            override fun checkpoint() {
                val reason = synchronized(lock) {
                    if (pauseReason == null && (rounds >= budget.rounds || tokens >= budget.tokens ||
                            (System.nanoTime() - grantStarted) / 1_000_000_000 >= budget.seconds)) {
                        pauseReason = "budget"
                        notices += Mail(text = "任务 $id 到达软预算，已在安全边界暂停。请查看事实/耗费后显式继续、缩小或接手。", revision = revision, important = true)
                    }
                    pauseReason?.also {
                        status = if (it == "decision") "waiting_decision" else "paused"
                        controller.pause()
                    }
                }
                if (reason != null) {
                    changed(this@Task)
                }
                controller.throwIfCancelled()
            }
            override fun drainMessages(): List<String> = synchronized(lock) {
                buildList {
                    addAll(guidance.filter { it.delivery == "queued" }.map {
                        it.delivery = "prepared"; "主代理引导 ${it.id} / 修订 ${it.revision}：\n${it.text}"
                    })
                    if (totalRounds >= 10 && latestReport.isBlank() && !reportReminderSent) {
                        reportReminderSent = true
                        add("已执行多轮但尚未提供阶段结论。请使用 subagent_report 提供已确认事实及证据、当前事项、下一步和阻塞；不需要提供隐藏思考链。")
                    }
                }
            }
            override fun requestStarted() = synchronized(lock) {
                guidance.filter { it.delivery == "prepared" }.forEach { it.delivery = "injected"; requestRevision = maxOf(requestRevision, it.revision) }
            }
            override fun responseReceived(usage: AgentTokenUsage?) = synchronized(lock) {
                guidance.filter { it.delivery == "injected" }.forEach { it.delivery = "response_received" }
                appliedRevision = requestRevision
                rounds++; totalRounds++
                val used = (usage?.inputTokens?.toLong() ?: 0) + (usage?.outputTokens?.toLong() ?: 0)
                tokens += used; totalTokens += used
            }
            override fun beforeFinish(): List<String> = drainMessages()
        }
        fun snapshot(includeResult: Boolean = false): JSONObject = synchronized(lock) {
            JSONObject().put("task_id", id).put("subagent_id", subagentId).put("invocation_id", invocationId)
                .put("description", description).put("status", status).put("revision", revision)
                .put("applied_revision", appliedRevision).put("rounds", totalRounds).put("observed_tokens", totalTokens)
                .put("budget_seconds", budget.seconds).put("budget_rounds", budget.rounds).put("budget_tokens", budget.tokens)
                .put("pause_reason", pauseReason ?: JSONObject.NULL)
                .put("latest_report", latestReport.ifBlank { "尚未提供阶段结论" })
                .put("guidance", JSONArray(guidance.map { JSONObject().put("message_id", it.id)
                    .put("revision", it.revision).put("delivery", it.delivery) }))
                .apply { if (includeResult) result?.let { put("result", JSONObject(it.content)) } }
        }
        fun hasUnconsumedGuidance(): Boolean = guidance.any { it.delivery != "response_received" }
        fun guide(text: String, revise: Boolean): JSONObject = synchronized(lock) {
            check(status !in TERMINAL && status != "cancelling") { "任务已结束或取消中，请用 subagent(resume=...) 续接" }
            check(guidance.size < 256) { "本轮引导达到保留上限，请结束后续接" }
            if (revise) revision++
            val mail = Mail(text = text, revision = revision)
            guidance += mail
            JSONObject().put("ok", true).put("message_id", mail.id).put("delivery", mail.delivery).put("revision", revision)
        }
    }

    private val lock = Any()
    private val tasks = linkedMapOf<String, Task>()
    private val notices = mutableListOf<Mail>()
    private var closed = false
    private var decisionReminderSent = false
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12),
        { runnable -> Thread(runnable, "eta-subtask").apply { isDaemon = true } })
    private val parentBinding = parent.register { cancelAll() }

    fun start(subagentId: String, invocationId: String, description: String, budget: Budget,
        onFinish: () -> Unit = {}, onState: (Task, String, String) -> Unit = { _, _, _ -> },
        run: (Task) -> AgentModelClient.ToolResult): Task {
        val task = synchronized(lock) {
            check(!closed && tasks.size < 16) { "当前 run 的子任务已达上限（16），请收齐后在后续 run 继续" }
            Task(subagentId, invocationId, description, budget).also { task ->
                task.onState = { state, reason -> onState(task, state, reason) }
                tasks[task.id] = task
            }
        }
        try {
            workers.execute {
                if (synchronized(lock) { task.status == "interrupted" }) {
                    runCatching(onFinish)
                    return@execute
                }
                synchronized(lock) { task.status = if (task.controller.isCancelled) "cancelling" else "running" }
                changed(task)
                try {
                    task.controller.throwIfCancelled()
                    val result = run(task)
                    synchronized(lock) {
                        if (task.status == "interrupted") return@execute
                        task.result = result
                        task.status = if (task.controller.isCancelled) "cancelled"
                            else if (!JSONObject(result.content).optBoolean("ok")) "failed"
                            else if (task.appliedRevision != task.revision) "completed_previous_revision"
                            else if (task.hasUnconsumedGuidance()) "completed_with_unconsumed_guidance"
                            else "completed"
                    }
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (task.status == "interrupted") return@execute
                        task.status = if (task.controller.isCancelled) "cancelled" else "failed"
                        task.result = AgentModelClient.ToolResult(JSONObject().put("ok", false)
                            .put("code", if (task.controller.isCancelled) "SUBAGENT_CANCELLED" else "SUBAGENT_FAILED")
                            .put("message", failure.message ?: failure.javaClass.simpleName).toString())
                    }
                } finally {
                    runCatching(onFinish)
                    synchronized(lock) {
                        task.finalized = true
                        if (task.status != "interrupted" && notices.size < 512) notices += Mail(text = task.snapshot(true).toString(), revision = task.revision, important = true)
                    }
                    changed(task)
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) { tasks.remove(task.id) }
            throw failure
        }
        return task
    }

    private fun changed(task: Task) {
        val state = synchronized(lock) { task.status to task.pauseReason.orEmpty() }
        runCatching { task.onState(state.first, state.second) }
        parent.signalActivity()
    }
    private fun active(): Boolean = synchronized(lock) { tasks.values.any { !it.finalized } }
    private fun important(): Boolean = synchronized(lock) { notices.any { it.important && it.delivery == "queued" } }
    private fun needsDecision(): Boolean = synchronized(lock) { tasks.values.any { it.status in PAUSED } }
    private fun drain(): List<String> = synchronized(lock) {
        notices.filter { it.delivery == "queued" }.map { it.delivery = "prepared"; "消息 ${it.id}：\n${it.text}" }
    }
    val coordination = object : AgentRunCoordination {
        override fun drainMessages() = drain()
        override fun requestStarted() = synchronized(lock) {
            notices.filter { it.delivery == "prepared" }.forEach { it.delivery = "injected" }
        }
        override fun responseReceived(usage: AgentTokenUsage?) = synchronized(lock) {
            notices.filter { it.delivery == "injected" }.forEach { it.delivery = "response_received" }
            notices.removeAll { it.delivery == "response_received" }
            Unit
        }
        override fun beforeFinish(): List<String> {
            // Do not seal the parent while a child is active. No repeated model polls on ordinary activity.
            while (active() && !important() && !needsDecision() && !parent.hasPendingSteering) {
                parent.awaitActivity(60_000) { important() || needsDecision() || !active() }
            }
            val messages = drain()
            if (messages.isEmpty() && needsDecision()) {
                check(!decisionReminderSent) { "主代理仍未处理暂停/待决策的子任务，本run已停止；任务证据与消息保留，不自动续费循环。" }
                decisionReminderSent = true
            }
            return if (messages.isEmpty() && needsDecision()) listOf(
                "仍有暂停/等待决策的子任务，结束父任务前请显式继续或取消：" +
                    synchronized(lock) { tasks.values.filter { it.status in PAUSED }.map { it.snapshot() } }.joinToString(),
            ) else messages
        }
    }

    fun report(task: Task, args: JSONObject): AgentModelClient.ToolResult {
        val text = args.optString("text").trim()
        require(text.isNotEmpty() && text.length <= 32_000) { "报告 text 必须非空且不超过32000字符；更长证据请按需分页" }
        val decision = args.optBoolean("needs_decision")
        val mail = synchronized(lock) {
            check(notices.size < 480) { "报告保留上限已到，请直接返回交接报告" }
            task.latestReport = text
            Mail(text = "任务 ${task.id} / 修订 ${task.revision} / ${if (decision) "需要决定" else "阶段事实"}：\n$text",
                revision = task.revision, important = decision).also { notices += it }
        }
        if (decision) {
            synchronized(lock) { task.pauseReason = "decision"; task.status = "pausing" }
            changed(task)
        }
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("message_id", mail.id)
            .put("delivery", "queued").put("will_pause_at_safe_boundary", decision).toString())
    }

    fun control(args: JSONObject): AgentModelClient.ToolResult = try {
        val action = args.optString("action")
        if (action in setOf("guide", "continue", "cancel")) decisionReminderSent = false
        val id = args.optString("task_id")
        val task = if (id.isBlank()) null else synchronized(lock) {
            tasks[id] ?: tasks.values.lastOrNull { it.subagentId == id }
        } ?: if (id.isBlank()) null else error("找不到当前父 run 的任务")
        val result = when (action) {
            "status", "result" -> JSONObject().put("ok", true).put("tasks", JSONArray(
                synchronized(lock) { (task?.let(::listOf) ?: tasks.values.toList()).map { it.snapshot(action == "result") } }))
            "wait" -> {
                val timeout = args.optLong("timeout_ms", 60_000).coerceIn(1, 60_000)
                parent.awaitActivity(timeout) { important() || (task?.let { synchronized(lock) { it.finalized || it.status in PAUSED } } ?: !active()) }
                JSONObject().put("ok", true).put("user_steering_pending", parent.hasPendingSteering)
                    .put("messages", JSONArray(drain())).put("tasks", JSONArray(synchronized(lock) {
                        (task?.let(::listOf) ?: tasks.values.toList()).map { it.snapshot() }
                    }))
            }
            "guide" -> {
                val text = args.optString("text").trim()
                require(text.isNotEmpty() && text.length <= 32_000) { "完整引导不能为空或超过32000字符" }
                requireNotNull(task) { "需要 task_id" }.guide(text, args.optBoolean("revise"))
            }
            "pause" -> {
                val target = requireNotNull(task) { "需要 task_id" }
                synchronized(lock) { check(target.status !in TERMINAL); target.pauseReason = "requested"; target.status = "pausing" }
                changed(target)
                JSONObject().put("ok", true).put("task", target.snapshot())
            }
            "continue" -> {
                val target = requireNotNull(task) { "需要 task_id" }
                synchronized(lock) {
                    check(target.status !in TERMINAL && target.status != "cancelling")
                    target.pauseReason = null; target.status = "running"
                    target.grantStarted = System.nanoTime(); target.rounds = 0; target.tokens = 0
                }
                target.controller.resume(); changed(target)
                JSONObject().put("ok", true).put("task", target.snapshot())
            }
            "cancel" -> {
                val target = requireNotNull(task) { "需要 task_id" }
                synchronized(lock) { check(target.status !in TERMINAL); target.status = "cancelling" }
                target.controller.cancel(); changed(target)
                JSONObject().put("ok", true).put("task", target.snapshot())
            }
            else -> error("未知 action：$action")
        }
        AgentModelClient.ToolResult(result.toString())
    } catch (failure: IllegalArgumentException) { error(failure) }
      catch (failure: IllegalStateException) { error(failure) }

    private fun error(failure: Exception) = AgentModelClient.ToolResult(JSONObject().put("ok", false)
        .put("code", "SUBAGENT_CONTROL_ERROR").put("message", failure.message).toString())
    private fun cancelAll() {
        val active = synchronized(lock) { tasks.values.filter { it.status !in TERMINAL }.toList() }
        active.forEach { task ->
            synchronized(lock) { task.status = "cancelling" }
            task.controller.cancel()
            changed(task)
        }
    }
    override fun close() {
        synchronized(lock) { closed = true }
        cancelAll()
        workers.shutdown()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        synchronized(lock) { tasks.values.filter { it.status !in TERMINAL }.toList() }.forEach { task ->
            synchronized(lock) { task.status = "interrupted" }
            changed(task)
        }
        parentBinding.close()
    }
    companion object {
        private val TERMINAL = setOf("completed", "failed", "cancelled", "completed_previous_revision", "completed_with_unconsumed_guidance", "interrupted")
        private val PAUSED = setOf("paused", "waiting_decision")
    }
}
