package io.github.mangi.eta.local.planmode

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PlanAction(val toolName: String, val arguments: String) {
    fun fingerprint(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((toolName + "\u0000" + arguments).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

@Serializable
internal data class PlanStep(
    val id: String,
    val title: String,
    val status: String = "pending",
    val actions: List<PlanAction> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val independentOfQuestions: Boolean = false,
    val nextAction: Int = 0,
)

@Serializable
internal data class PlanRecord(
    val planId: String,
    val conversationId: String,
    val runId: String,
    val revision: Int,
    val title: String,
    val content: String,
    val status: String,
    val phase: String,
    val active: Boolean,
    val steps: List<PlanStep>,
    val approvedRevision: Int? = null,
    val questionBlocked: Boolean = false,
    val independenceApproved: Boolean = false,
    val interruptedWork: Boolean = false,
    val editPending: Boolean = false,
    // Hashes of attempted actions whose outcomes must not be replayed after continuation/restart.
    val uncertainActions: List<String> = emptyList(),
    val planLineageId: String = "",
    val persistenceWarning: String = "",
)

/** Process-local execution leases are never restored from disk after process death. */
internal object PlanModeStore {
    internal const val APPROVAL_WAIT_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: AtomicFile? = null
    private val mutable = MutableStateFlow<List<PlanRecord>>(emptyList())
    val plans = mutable.asStateFlow()
    private val listeners = mutableMapOf<String, () -> Unit>()
    private val approvalDeadlines = mutableMapOf<String, Long>()
    private val approvalAllowed = mutableMapOf<String, () -> Boolean>()
    private var injectWriteFailure = false
    private var nowNanos: () -> Long = System::nanoTime

    @Synchronized internal fun failNextWriteForTest() { injectWriteFailure = true }
    @Synchronized internal fun setClockForTest(clock: () -> Long) { nowNanos = clock }

    @Synchronized fun initialize(context: Context) = initializeAt(context.filesDir)

    @Synchronized internal fun initializeAt(filesDir: File) {
        if (file != null) return
        val storage = AtomicFile(File(filesDir, "planmode/plans.json"))
        val backup = File(storage.baseFile.path + ".bak")
        val loaded = if (storage.baseFile.exists() || backup.exists()) {
            val text = storage.openRead().use {
                require(it.channel.size() <= 1048576) { "计划记录过大" }
                it.readBytes().toString(Charsets.UTF_8)
            }
            json.decodeFromString<List<PlanRecord>>(text).takeLast(32).map { plan ->
                // Older records predate action hashes; recover the pending/running action conservatively.
                val recovered = plan.steps.filter { it.status == "running" || it.status == "blocked" }
                    .mapNotNull { it.actions.getOrNull(it.nextAction)?.fingerprint() }
                // A finished/blocked record is not an execution lease. Preserve its
                // observed outcome; an interrupted active run cannot be declared complete.
                val restoredPhase = if (plan.active || recovered.isNotEmpty()) {
                    if (plan.status == "approved" || plan.questionBlocked || recovered.isNotEmpty()) "partially_blocked" else "ended"
                } else plan.phase
                plan.copy(active = false, approvedRevision = null, phase = restoredPhase,
                    status = if (plan.active || plan.status == "approved") "ended" else plan.status,
                    interruptedWork = plan.interruptedWork || recovered.isNotEmpty(),
                    uncertainActions = (plan.uncertainActions + recovered).distinct(),
                    steps = plan.steps.map { if (it.status == "running") it.copy(status = "blocked") else it })
            }
        } else emptyList()
        file = storage
        mutable.value = loaded
    }

    private fun publish(next: List<PlanRecord>) {
        val storage = checkNotNull(file)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1048576) { "计划记录超过限额" }
        storage.baseFile.parentFile?.mkdirs()
        val out = storage.startWrite()
        try {
            out.write(bytes)
            if (injectWriteFailure) { injectWriteFailure = false; throw java.io.IOException("Injected plan write failure") }
            out.fd.sync()
            storage.finishWrite(out)
        }
        catch (e: Exception) { storage.failWrite(out); throw e }
        check(storage.openRead().use { it.readBytes().contentEquals(bytes) }) { "计划记录落盘校验失败" }
        mutable.value = next
        listeners.values.toList().forEach { runCatching { it() } }
    }

    @Synchronized internal fun listen(runId: String, wake: () -> Unit, canApprove: () -> Boolean = { true }) {
        listeners[runId] = wake; approvalAllowed[runId] = canApprove
    }
    @Synchronized internal fun unlisten(runId: String) { listeners.remove(runId); approvalAllowed.remove(runId) }
    internal fun current(runId: String): PlanRecord? = plans.value.lastOrNull { it.runId == runId && it.active }

    @Synchronized internal fun propose(conversationId: String, runId: String, title: String, content: String, steps: List<PlanStep>,
        questionBlocked: Boolean = false, expectedRevision: Int? = null, planLineageId: String = runId,
        mayPublish: () -> Boolean = { true }): PlanRecord {
        require(conversationId.isNotBlank() && runId.isNotBlank() && title.isNotBlank() && title.length <= 200 && content.isNotBlank() && content.length <= 40000)
        require(steps.isNotEmpty() && steps.size <= 40)
        require(steps.map { it.id }.toSet().size == steps.size)
        val prior = current(runId)
        require(prior == null || prior.conversationId == conversationId) { "运行身份不属于当前会话" }
        require(prior?.revision == expectedRevision) { "计划版本已变化；旧提案未提交" }
        require(mayPublish()) { "用户引导或任务状态已变化；旧提案未提交" }
        val inherited = mutable.value.filter { it.conversationId == conversationId && it.planLineageId.ifBlank { it.runId } == planLineageId }
            .flatMap { it.uncertainActions }.distinct()
        val record = PlanRecord(prior?.planId ?: UUID.randomUUID().toString(), conversationId, runId,
            (prior?.revision ?: 0) + 1, title, content, "awaiting_approval", "planning", true, steps, questionBlocked = questionBlocked, interruptedWork = prior?.interruptedWork == true || prior?.steps?.any { it.status == "running" } == true || inherited.isNotEmpty(),
            uncertainActions = inherited, planLineageId = planLineageId)
        val all = mutable.value.filterNot { it.planId == record.planId }.toMutableList()
        while (all.size >= 32) all.removeAt(all.indexOfFirst { old ->
            !old.active && (old.uncertainActions.isEmpty() ||
                (old.conversationId == conversationId && old.planLineageId.ifBlank { old.runId } == planLineageId))
        }.also { require(it >= 0) { "计划记录过多且包含未核实的操作，未丢弃旧记录" } })
        publish(all + record)
        approvalDeadlines[runId] = nowNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(APPROVAL_WAIT_MS)
        return record
    }

    @Synchronized fun approve(planId: String, expectedRevision: Int): Boolean {
        val p = mutable.value.firstOrNull { it.planId == planId } ?: return false
        if (!p.active || p.revision != expectedRevision || p.status != "awaiting_approval" || p.steps.isEmpty() ||
            nowNanos() >= (approvalDeadlines[p.runId] ?: 0L) || approvalAllowed[p.runId]?.invoke() != true) return false
        // Approval applies to the independence flags displayed in this exact revision,
        // even if the question is not asked or becomes unanswered until later.
        replace(p.copy(status = "approved", phase = if (p.questionBlocked) "partially_blocked" else "running",
            approvedRevision = p.revision, independenceApproved = p.steps.any { it.independentOfQuestions }))
        approvalDeadlines.remove(p.runId)
        return true
    }

    @Synchronized fun edit(planId: String, expectedRevision: Int, content: String): Boolean {
        val p = mutable.value.firstOrNull { it.planId == planId } ?: return false
        if (!p.active || p.revision != expectedRevision || content.isBlank() || content.length > 40000) return false
        // Model must propose new exact actions before approval. In-flight work cannot be undone.
        replace(p.copy(revision = p.revision + 1, content = content, status = "draft", phase = "planning",
            approvedRevision = null, steps = emptyList(), questionBlocked = true, independenceApproved = false,
            interruptedWork = p.interruptedWork || p.steps.any { it.status == "running" }, editPending = true))
        approvalDeadlines.remove(p.runId)
        return true
    }

    @Synchronized fun defer(planId: String, expectedRevision: Int): Boolean {
        val p = mutable.value.firstOrNull { it.planId == planId } ?: return false
        if (!p.active || p.revision != expectedRevision || p.status != "awaiting_approval") return false
        replace(p.copy(status = "unanswered", phase = "partially_blocked", approvedRevision = null))
        approvalDeadlines.remove(p.runId)
        return true
    }

    @Synchronized internal fun expireApproval(runId: String, revision: Int) {
        val p = current(runId) ?: return
        if (p.revision == revision && p.status == "awaiting_approval") {
            replace(p.copy(status = "unanswered", phase = "partially_blocked", approvedRevision = null))
            approvalDeadlines.remove(runId)
        }
    }

    @Synchronized internal fun invalidateForSteering(runId: String) {
        val p = current(runId) ?: return
        replace(p.copy(revision = p.revision + 1, status = "draft", phase = "planning",
            approvedRevision = null, steps = emptyList(), questionBlocked = true, independenceApproved = false,
            interruptedWork = p.interruptedWork || p.steps.any { it.status == "running" }, editPending = p.editPending))
        approvalDeadlines.remove(runId)
    }

    @Synchronized internal fun markQuestionsBlocked(runId: String) {
        val p = current(runId) ?: return
        if (!p.questionBlocked) replace(p.copy(questionBlocked = true, phase = "partially_blocked"))
    }

    @Synchronized internal fun approvalRemainingMs(runId: String, revision: Int): Long {
        val p = current(runId) ?: return 0
        if (p.revision != revision || p.status != "awaiting_approval") return 0
        val remaining = (approvalDeadlines[runId] ?: return 0) - nowNanos()
        if (remaining <= 0L) return 0
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining - 1L) + 1L
    }

    /** Re-check the current step, exact action and safety epoch in the same lock as edits. */
    @Synchronized internal fun reserveAction(runId: String, revision: Int, expected: PlanStep, action: PlanAction,
        fingerprint: String, mayReserve: () -> Boolean): Boolean {
        val p = current(runId) ?: return false
        val step = p.steps.firstOrNull { it.id == expected.id } ?: return false
        if (p.status != "approved" || p.revision != revision || p.approvedRevision != revision || step != expected ||
            step.status != "pending" || step.actions.getOrNull(step.nextAction) != action ||
            step.dependsOn.any { dep -> p.steps.none { it.id == dep && it.status == "completed" } } ||
            (p.questionBlocked && (!p.independenceApproved || !step.independentOfQuestions)) ||
            fingerprint in p.uncertainActions || !mayReserve()) return false
        replace(p.copy(steps = p.steps.map { if (it.id == step.id) step.copy(status = "running") else it },
            uncertainActions = p.uncertainActions + fingerprint))
        return true
    }

    /** Reservation lost a race before dispatch: release only its still-pending marker, never run the tool. */
    @Synchronized internal fun abortReservation(runId: String, revision: Int, stepId: String, nextAction: Int, fingerprint: String) {
        val p = current(runId) ?: return
        if (fingerprint !in p.uncertainActions) return
        val steps = if (p.revision == revision) p.steps.map { step ->
            if (step.id == stepId && step.status == "running" && step.nextAction == nextAction) step.copy(status = "pending") else step
        } else p.steps
        replace(p.copy(steps = steps, uncertainActions = p.uncertainActions - fingerprint))
    }

    // The caller treats this check as the dispatch boundary; edits after it cannot undo in-flight I/O.
    @Synchronized internal fun canDispatch(runId: String, revision: Int, stepId: String, nextAction: Int,
        fingerprint: String, mayDispatch: () -> Boolean): Boolean {
        val p = current(runId) ?: return false
        val step = p.steps.firstOrNull { it.id == stepId } ?: return false
        return p.status == "approved" && p.revision == revision && p.approvedRevision == revision &&
            step.status == "running" && step.nextAction == nextAction && fingerprint in p.uncertainActions &&
            step.dependsOn.all { dep -> p.steps.any { it.id == dep && it.status == "completed" } } &&
            (!p.questionBlocked || (p.independenceApproved && step.independentOfQuestions)) && mayDispatch()
    }

    @Synchronized internal fun finishAction(runId: String, revision: Int, stepId: String, expectedAction: Int,
        fingerprint: String, success: Boolean): Boolean {
        val p = current(runId) ?: return false
        val step = p.steps.firstOrNull { it.id == stepId } ?: return false
        if (p.revision != revision || p.approvedRevision != revision || step.status != "running" || step.nextAction != expectedAction) return false
        val next = step.nextAction + 1
        val updated = step.copy(status = if (!success) "blocked" else if (next == step.actions.size) "completed" else "pending",
            nextAction = if (success) next else step.nextAction)
        val steps = p.steps.map { if (it.id == step.id) updated else it }
        val phase = when {
            p.questionBlocked || steps.any { it.status == "blocked" || it.status == "failed" } -> "partially_blocked"
            else -> "running"
        }
        replace(p.copy(steps = steps, phase = phase,
            uncertainActions = if (success) p.uncertainActions - fingerprint else p.uncertainActions,
            interruptedWork = p.interruptedWork || !success))
        return true
    }

    @Synchronized internal fun end(runId: String, completedRun: Boolean = false) {
        val p = current(runId) ?: return
        val wasRunning = p.steps.any { it.status == "running" }
        val allActionsDone = p.steps.isNotEmpty() && p.steps.all { it.status == "completed" }
        // A successful provider turn and completed exact actions do not prove
        // real-world verification; only the action plan itself is complete.
        val phase = when {
            completedRun && p.status == "approved" && allActionsDone && !p.questionBlocked && !p.interruptedWork -> "completed"
            p.status == "approved" || p.questionBlocked || wasRunning || p.steps.any { it.status == "blocked" || it.status == "failed" } -> "partially_blocked"
            p.phase == "partially_blocked" -> p.phase
            else -> "ended"
        }
        val ended = p.copy(active = false, approvedRevision = null, phase = phase,
            status = if (p.status == "approved") "ended" else p.status,
            interruptedWork = p.interruptedWork || wasRunning || (!completedRun && p.status == "approved"),
            steps = p.steps.map { if (it.status == "running") it.copy(status = "blocked") else it })
        try {
            replace(ended)
        } catch (failure: Exception) {
            // Never leave a dead run showing an active approval if the final write
            // fails. The previous snapshot remains on disk and the error propagates.
            mutable.value = mutable.value.map { if (it.planId == ended.planId) ended.copy(
                persistenceWarning = "计划最终状态保存未确认；当前运行已结束，不能据此重试操作。重启后请先核实执行结果。") else it }
            throw failure
        } finally {
            approvalDeadlines.remove(runId)
        }
    }

    private fun replace(plan: PlanRecord) = publish(mutable.value.map { if (it.planId == plan.planId) plan else it })
    @Synchronized internal fun resetForTest() { file = null; mutable.value = emptyList(); listeners.clear(); approvalDeadlines.clear(); approvalAllowed.clear(); injectWriteFailure = false; nowNanos = System::nanoTime }
}
