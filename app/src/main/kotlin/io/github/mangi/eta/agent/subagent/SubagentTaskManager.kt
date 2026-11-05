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
internal class SubagentTaskManager(
    private val parent: AgentRunController,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    internal data class Budget(
        val seconds: Long = DEFAULT_SECONDS,
        val rounds: Int = DEFAULT_ROUNDS,
        val tokens: Long = DEFAULT_TOKENS,
    ) {
        companion object {
            const val DEFAULT_SECONDS = 900L
            const val DEFAULT_ROUNDS = 100
            const val DEFAULT_TOKENS = 300_000L
            const val MAX_SECONDS = 7_200L
            const val MAX_ROUNDS = 1_000
            const val MAX_TOKENS = 10_000_000L

            /** Launch budget: explicit arguments win; otherwise the profile default (engineering 6M, read-only 300k). */
            fun from(args: JSONObject, profile: SubagentProfile? = null) = Budget(
                seconds = args.optLong("max_time_seconds", DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS),
                rounds = args.optInt("max_rounds", DEFAULT_ROUNDS).coerceIn(1, MAX_ROUNDS),
                tokens = args.optLong("max_tokens", profile?.defaultTokenBudget ?: DEFAULT_TOKENS)
                    .coerceIn(1, MAX_TOKENS),
            )

            /**
             * Field-by-field overrides for one new grant window. Omitted fields keep the current value.
             * Every supplied value is validated before the task changes state, so a rejected argument
             * never leaves a half-applied budget.
             */
            fun overridden(base: Budget, args: JSONObject): Budget = base.copy(
                seconds = args.longOverride("max_time_seconds", base.seconds, 1, MAX_SECONDS),
                rounds = args.longOverride("max_rounds", base.rounds.toLong(), 1, MAX_ROUNDS).toInt(),
                tokens = args.longOverride("max_tokens", base.tokens, 1, MAX_TOKENS),
            )

            private fun JSONObject.longOverride(key: String, current: Long, min: Long, max: Long): Long {
                if (!has(key) || isNull(key)) return current
                val raw = opt(key)
                require(raw is Number) { "$key 必须是整数" }
                val value = raw.toDouble()
                require(value == kotlin.math.floor(value) && value >= min && value <= max.toDouble()) {
                    "$key 必须是 $min..$max 的整数"
                }
                return value.toLong()
            }
        }
    }

    private data class Mail(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val revision: Int,
        val important: Boolean = false,
        val kind: String = "coordination",
        var delivery: String = "queued",
    )

    internal inner class Task(
        val subagentId: String,
        val invocationId: String,
        val description: String,
        initialBudget: Budget,
    ) {
        @Volatile var budget: Budget = initialBudget
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
        var pauseDetail: String? = null
        var budgetHit: List<String> = emptyList()
        var windowStarted = 0L
        private val createdStarted = nanoTime()
        var rounds = 0
        var tokens = 0L
        var totalRounds = 0
        var totalTokens = 0L
        var grants = 1
        private val guidance = mutableListOf<Mail>()
        var latestReport = ""
        var latestReportKind = ""
        private val progressReports = mutableListOf<Pair<Int, String>>()
        private var budgetWarningSent = false

        fun windowSeconds(): Long =
            if (windowStarted == 0L) 0L else ((nanoTime() - windowStarted) / 1_000_000_000).coerceAtLeast(0)

        fun totalSeconds(): Long = ((nanoTime() - createdStarted) / 1_000_000_000).coerceAtLeast(0)

        private fun budgetHits(): List<String> = buildList {
            if (rounds >= budget.rounds) add(HIT_ROUNDS)
            if (tokens >= budget.tokens) add(HIT_TOKENS)
            if (windowSeconds() >= budget.seconds) add(HIT_TIME)
        }

        /** Budget pause text always separates the exhausted window from the cumulative run cost. */
        private fun budgetDetail(hits: List<String>): String = buildString {
            append("预算窗口用尽（")
            append(hits.joinToString("、") { hit -> when (hit) {
                HIT_TOKENS -> "token"
                HIT_ROUNDS -> "round"
                else -> "time"
            } })
            append("）：token 窗口 $tokens/${budget.tokens}，累计 $totalTokens；")
            append("round 窗口 $rounds/${budget.rounds}，累计 $totalRounds；")
            append("time 窗口 ${windowSeconds()}s/${budget.seconds}s，累计 ${totalSeconds()}s。")
            append("累计用量含重复历史输入，不是上下文长度或净计费。")
        }

        private fun nearBudget(): Boolean {
            fun near(used: Long, limit: Long) = limit > 0 && used * 5 >= limit * 4
            return near(rounds.toLong(), budget.rounds.toLong()) || near(tokens, budget.tokens) ||
                near(windowSeconds(), budget.seconds)
        }

        private fun budgetPercent(): Int {
            val ratios = listOf(
                rounds.toDouble() / budget.rounds.coerceAtLeast(1),
                tokens.toDouble() / budget.tokens.coerceAtLeast(1),
                windowSeconds().toDouble() / budget.seconds.coerceAtLeast(1),
            )
            return (ratios.max() * 100).toInt().coerceIn(0, 100)
        }

        val coordination = object : AgentRunCoordination {
            override fun checkpoint() {
                val reason = synchronized(lock) {
                    if (pauseReason == null) {
                        val hits = budgetHits()
                        if (hits.isNotEmpty()) {
                            pauseReason = "budget"
                            budgetHit = hits
                            pauseDetail = budgetDetail(hits)
                            if (notices.size < 512) {
                                notices += Mail(
                                    text = "任务 $id 到达软预算，已在安全边界暂停：$pauseDetail" +
                                        "请查看事实与耗费后显式继续（可在同一 continue 中调整预算）、缩小或接手。",
                                    revision = revision,
                                    important = true,
                                    kind = REPORT_DECISION,
                                )
                            }
                        }
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
                        it.delivery = "prepared"
                        "主代理引导 ${it.id} / 修订 ${it.revision}：\n${it.text}"
                    })
                    // No round-count nagging: only a near-exhausted window is worth one coordination reminder.
                    if (!budgetWarningSent && pauseReason == null && nearBudget()) {
                        budgetWarningSent = true
                        add(
                            "当前预算窗口已使用约 ${budgetPercent()}%。若已接近完成，请用 subagent_report(kind=\"coordination\"/\"final\")" +
                                " 交接已确认事实、证据与剩余事项；普通进度请用 kind=\"progress\"，它只更新详情，不打断主代理。",
                        )
                    }
                }
            }

            override fun requestStarted() = synchronized(lock) {
                guidance.filter { it.delivery == "prepared" }.forEach {
                    it.delivery = "injected"
                    requestRevision = maxOf(requestRevision, it.revision)
                }
            }

            override fun responseReceived(usage: AgentTokenUsage?) = synchronized(lock) {
                guidance.filter { it.delivery == "injected" }.forEach { it.delivery = "response_received" }
                appliedRevision = requestRevision
                rounds++
                totalRounds++
                val used = (usage?.inputTokens?.toLong() ?: 0) + (usage?.outputTokens?.toLong() ?: 0)
                tokens += used
                totalTokens += used
            }

            override fun beforeFinish(): List<String> = drainMessages()
        }

        fun snapshot(includeResult: Boolean = false): JSONObject = synchronized(lock) {
            JSONObject().put("task_id", id).put("subagent_id", subagentId).put("invocation_id", invocationId)
                .put("description", description).put("status", status).put("revision", revision)
                .put("applied_revision", appliedRevision).put("rounds", totalRounds).put("observed_tokens", totalTokens)
                .put("budget_seconds", budget.seconds).put("budget_rounds", budget.rounds).put("budget_tokens", budget.tokens)
                .put("window_rounds", rounds).put("window_tokens", tokens).put("window_seconds", windowSeconds())
                .put("total_rounds", totalRounds).put("total_tokens", totalTokens).put("total_seconds", totalSeconds())
                .put("grants", grants)
                .put("pause_reason", pauseReason ?: JSONObject.NULL)
                .put("pause_detail", pauseDetail ?: JSONObject.NULL)
                .put("budget_hit", JSONArray(budgetHit))
                .put("latest_report", latestReport.ifBlank { "尚未提供阶段结论" })
                .put("latest_report_kind", latestReportKind.ifBlank { JSONObject.NULL })
                .put("recent_progress", JSONArray(progressReports.map { (rev, text) ->
                    JSONObject().put("revision", rev).put("text", text)
                }))
                .put("guidance", JSONArray(guidance.map {
                    JSONObject().put("message_id", it.id).put("revision", it.revision).put("delivery", it.delivery)
                }))
                .apply { if (includeResult) result?.let { put("result", JSONObject(it.content)) } }
        }

        fun hasUnconsumedGuidance(): Boolean = guidance.any { it.delivery != "response_received" }

        fun guide(text: String, revise: Boolean): JSONObject = synchronized(lock) {
            check(status !in TERMINAL && status != "cancelling") { "任务已结束或取消中，请用 subagent(resume=...) 续接" }
            check(guidance.size < 256) { "本轮引导达到保留上限，请结束后续接" }
            if (revise) revision++
            val mail = Mail(text = text, revision = revision)
            guidance += mail
            JSONObject().put("ok", true).put("message_id", mail.id).put("delivery", mail.delivery)
                .put("revision", revision).put("paused_not_resumed", status in PAUSED)
        }

        /**
         * `continue` grants one new window: budget, guidance, revision and counters are applied in a
         * single locked step after every argument has been validated.
         */
        fun applyGrant(next: Budget, text: String, revise: Boolean): JSONObject = synchronized(lock) {
            check(status !in TERMINAL && status != "cancelling") { "任务已结束或取消中，请用 subagent(resume=...) 续接" }
            check(guidance.size < 256) { "本轮引导达到保留上限，请结束后续接" }
            budget = next
            grants++
            if (revise) revision++
            val mail = if (text.isNotEmpty()) Mail(text = text, revision = revision).also { guidance += it } else null
            pauseReason = null
            pauseDetail = null
            budgetHit = emptyList()
            // A task still waiting for a worker keeps queued; the worker sets running when it starts.
            if (status != "queued") status = "running"
            windowStarted = nanoTime()
            rounds = 0
            tokens = 0
            budgetWarningSent = false
            JSONObject().put("ok", true).put("task", snapshot()).apply {
                if (mail != null) put("message_id", mail.id).put("guidance_delivery", mail.delivery)
            }
        }

        fun recordProgress(text: String) = synchronized(lock) {
            latestReport = text
            latestReportKind = REPORT_PROGRESS
            progressReports += revision to text.take(PROGRESS_DETAIL_CHARS)
            while (progressReports.size > MAX_PROGRESS_REPORTS) progressReports.removeAt(0)
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
                synchronized(lock) {
                    task.status = if (task.controller.isCancelled) "cancelling" else "running"
                    // Queue wait must not consume the execution window.
                    task.windowStarted = nanoTime()
                }
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
                        if (task.status != "interrupted" && notices.size < 512) {
                            notices += Mail(text = task.snapshot(true).toString(), revision = task.revision,
                                important = true, kind = REPORT_FINAL)
                        }
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
        val state = synchronized(lock) {
            val paused = task.status in PAUSED || task.status == "pausing"
            task.status to (if (paused) task.pauseDetail ?: task.pauseReason.orEmpty() else task.pauseReason.orEmpty())
        }
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

    /**
     * Stage report. `kind=progress` only updates task detail and never enters the wake-up mailbox;
     * `coordination` (default for legacy calls) and `final` are preserved in full; `needs_decision`
     * always wins over `kind=progress` so a decision cannot be silently swallowed.
     */
    fun report(task: Task, args: JSONObject): AgentModelClient.ToolResult {
        val text = args.optString("text").trim()
        require(text.isNotEmpty() && text.length <= 32_000) { "报告 text 必须非空且不超过32000字符；更长证据请按需分页" }
        val requested = args.optString("kind").trim().lowercase()
        require(requested.isEmpty() || requested in REPORT_KINDS) {
            "kind 只能是 progress、coordination 或 final；省略等价于 coordination"
        }
        val decision = args.optBoolean("needs_decision")
        val kind = when {
            decision -> REPORT_DECISION
            requested.isEmpty() -> REPORT_COORDINATION
            else -> requested
        }
        if (kind == REPORT_PROGRESS) {
            task.recordProgress(text)
            return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("kind", kind)
                .put("delivery", "detail_only").put("in_mailbox", false)
                .put("will_pause_at_safe_boundary", false).toString())
        }
        val mail = synchronized(lock) {
            check(notices.size < 480) { "报告保留上限已到，请直接返回交接报告" }
            task.latestReport = text
            task.latestReportKind = kind
            Mail(text = "任务 ${task.id} / 修订 ${task.revision} / ${reportLabel(kind)}：\n$text",
                revision = task.revision, important = true, kind = kind)
                .also { notices += it }
        }
        if (kind == REPORT_DECISION) {
            synchronized(lock) {
                task.pauseReason = "decision"
                // The decision reason replaces any earlier budget pause detail.
                task.pauseDetail = null
                task.status = "pausing"
            }
            changed(task)
        }
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("kind", kind)
            .put("message_id", mail.id).put("delivery", "queued").put("in_mailbox", true)
            .put("will_pause_at_safe_boundary", kind == REPORT_DECISION).toString())
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
                synchronized(lock) {
                    check(target.status !in TERMINAL)
                    target.pauseReason = "requested"
                    target.pauseDetail = "主代理请求暂停：当前模型请求或工具批次结束后在安全边界停下，已执行操作不会撤销。"
                    target.status = "pausing"
                }
                changed(target)
                JSONObject().put("ok", true).put("task", target.snapshot())
            }
            "continue" -> {
                val target = requireNotNull(task) { "需要 task_id" }
                val text = args.optString("text").trim()
                require(text.length <= 32_000) { "引导不能超过32000字符；更长内容请分页或写入文件" }
                // Validate every budget override before touching task state.
                val next = Budget.overridden(target.budget, args)
                val applied = target.applyGrant(next, text, args.optBoolean("revise"))
                target.controller.resume()
                changed(target)
                applied
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
        const val REPORT_PROGRESS = "progress"
        const val REPORT_COORDINATION = "coordination"
        const val REPORT_FINAL = "final"
        const val REPORT_DECISION = "decision"
        const val HIT_TOKENS = "tokens"
        const val HIT_ROUNDS = "rounds"
        const val HIT_TIME = "time"
        private val REPORT_KINDS = setOf(REPORT_PROGRESS, REPORT_COORDINATION, REPORT_FINAL)
        private const val MAX_PROGRESS_REPORTS = 8
        private const val PROGRESS_DETAIL_CHARS = 4_000
        private fun reportLabel(kind: String) = when (kind) {
            REPORT_PROGRESS -> "普通进度"
            REPORT_FINAL -> "最终交接"
            REPORT_DECISION -> "需要决定"
            else -> "阶段事实"
        }
        private val TERMINAL = setOf("completed", "failed", "cancelled", "completed_previous_revision", "completed_with_unconsumed_guidance", "interrupted")
        private val PAUSED = setOf("paused", "waiting_decision")
    }
}
