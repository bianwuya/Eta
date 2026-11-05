package io.github.mangi.eta.local.userquestions

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Same-process store. Persistence retains drafts, not fictitious live waiters after a process restart. */
internal object UserQuestionStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: AtomicFile? = null
    private val mutable = MutableStateFlow<List<UserQuestionSet>>(emptyList())
    val forms = mutable.asStateFlow()
    private val waiters = mutableMapOf<String, CompletableFuture<String>>()
    private var injectWriteFailure = false

    // Simulates process loss without deleting persisted data or reviving old futures.
    @Synchronized
    internal fun resetForTest() {
        file = null
        waiters.clear()
        mutable.value = emptyList()
        injectWriteFailure = false
    }

    @Synchronized
    internal fun failNextWriteForTest() { injectWriteFailure = true }

    @Synchronized
    fun initialize(context: Context) {
        if (file != null) return
        val storage = AtomicFile(File(context.filesDir, "userquestions/drafts.json"))
        // A legacy AtomicFile backup can exist without the base after interruption.
        val backup = File(storage.baseFile.path + ".bak")
        val loaded = if (storage.baseFile.exists() || backup.exists()) {
            val text = storage.openRead().use {
                require(it.channel.size() <= 524288) { "提问草稿文件过大" }
                it.readBytes().toString(Charsets.UTF_8)
            }
            json.decodeFromString<List<UserQuestionSet>>(text)
                .takeLast(16).map { it.copy(active = false, status = if (it.status == "waiting") "ended" else it.status) }
        } else emptyList()
        file = storage
        mutable.value = loaded
    }

    private fun publish(next: List<UserQuestionSet>) {
        val storage = checkNotNull(file)
        val bytes = json.encodeToString(next).toByteArray()
        require(bytes.size <= 524288) { "提问草稿超过限额" }
        storage.baseFile.parentFile?.mkdirs()
        val output = storage.startWrite()
        try {
            output.write(bytes)
            if (injectWriteFailure) {
                injectWriteFailure = false
                throw java.io.IOException("Injected test write failure")
            }
            // AtomicFile may only log sync failures; require an observable sync first.
            output.fd.sync()
            storage.finishWrite(output)
        } catch (failure: Exception) { storage.failWrite(output); throw failure }
        // AtomicFile.finishWrite() may only log a failed sync/rename; do not acknowledge
        // the new state unless the readable file actually contains this snapshot.
        check(storage.openRead().use { it.readBytes().contentEquals(bytes) }) { "提问草稿落盘校验失败" }
        mutable.value = next
    }

    @Synchronized
    fun open(conversationId: String, runId: String, callId: String, args: JSONObject): Pair<String, CompletableFuture<String>> {
        mutable.value.firstOrNull { it.runId == runId && it.callId == callId }?.let { existing ->
            return existing.id to (waiters[existing.id] ?: CompletableFuture.completedFuture(
                if (existing.status == "answered") answers(existing) else unanswered("原任务已结束，草稿仍保留")))
        }
        val (intro, questions) = UserQuestionParser.parse(args)
        require(mutable.value.count { it.active } < 16) { "等待回答的任务过多" }
        val form = UserQuestionSet(UUID.randomUUID().toString(), conversationId, runId, callId, intro, questions,
            questions.map { QuestionDraft(otherSelected = it.options.isEmpty()) })
        val future = CompletableFuture<String>()
        val retained = mutable.value.toMutableList()
        while (retained.size >= 16) retained.removeAt(retained.indexOfFirst { !it.active }.also { require(it >= 0) })
        publish(retained + form)
        waiters[form.id] = future
        return form.id to future
    }

    private fun snapshot(form: UserQuestionSet, drafts: List<QuestionDraft>): List<QuestionDraft>? {
        if (drafts.size != form.questions.size) return null
        // Detach caller-owned mutable lists and sets before publishing the snapshot.
        val copy = drafts.map { it.copy(selected = it.selected.toSet()) }
        return copy.takeIf { it.indices.all { index -> UserQuestionParser.validDraft(form.questions[index], it[index]) } }
    }

    @Synchronized
    fun setPresentation(id: String, presentation: UserQuestionPresentation): Boolean {
        val form = mutable.value.firstOrNull { it.id == id } ?: return false
        if (!form.active || form.status != "waiting" || waiters[id] == null) return false
        if (form.presentation != presentation) {
            publish(mutable.value.map { if (it.id == id) it.copy(presentation = presentation) else it })
        }
        return true
    }

    @Synchronized
    fun saveDrafts(id: String, drafts: List<QuestionDraft>): Boolean {
        val form = mutable.value.firstOrNull { it.id == id } ?: return false
        if (!form.active || form.status != "waiting" || waiters[id] == null) return false
        val next = snapshot(form, drafts) ?: return false
        if (form.drafts != next) publish(mutable.value.map { if (it.id == id) it.copy(drafts = next) else it })
        return true
    }

    @Synchronized
    fun draft(id: String, index: Int, value: QuestionDraft) {
        val form = mutable.value.firstOrNull { it.id == id } ?: return
        if (!form.active || form.status != "waiting" || index !in form.questions.indices) return
        require(UserQuestionParser.validDraft(form.questions[index], value))
        val drafts = form.drafts.toMutableList().also { it[index] = value }
        saveDrafts(id, drafts)
    }

    @Synchronized
    fun submit(id: String, latestDrafts: List<QuestionDraft>? = null): Boolean {
        val form = mutable.value.firstOrNull { it.id == id } ?: return false
        val waiter = waiters[id] ?: return false
        if (!form.active || form.status != "waiting") return false
        val drafts = snapshot(form, latestDrafts ?: form.drafts) ?: return false
        if (!form.questions.indices.all { UserQuestionParser.valid(form.questions[it], drafts[it]) }) return false
        val settled = form.copy(drafts = drafts, active = false, status = "answered", summary = summary(form, drafts))
        publish(mutable.value.map { if (it.id == id) settled else it })
        waiters.remove(id)
        waiter.complete(answers(settled))
        return true
    }

    @Synchronized
    fun defer(id: String, latestDrafts: List<QuestionDraft>): Boolean {
        val form = mutable.value.firstOrNull { it.id == id } ?: return false
        val waiter = waiters[id] ?: return false
        if (!form.active || form.status != "waiting") return false
        val drafts = snapshot(form, latestDrafts) ?: return false
        val settled = form.copy(drafts = drafts, active = false, status = "unanswered")
        publish(mutable.value.map { if (it.id == id) settled else it })
        waiters.remove(id)
        waiter.complete(unanswered("用户选择稍后回答；这不代表同意"))
        return true
    }

    @Synchronized
    fun dismiss(id: String, message: String = "用户选择稍后回答；这不代表同意") {
        val form = mutable.value.firstOrNull { it.id == id } ?: return
        if (!form.active || form.status != "waiting") return
        val next = mutable.value.map { if (it.id == id) it.copy(active = false, status = "unanswered") else it }
        // Legacy dismissal is also used to release a cancelled Host. Even when the
        // disk write fails it must wake that Host; propagate the failure rather than
        // claiming durable persistence. User-facing "later" uses defer instead.
        try { publish(next) }
        finally {
            mutable.value = next
            waiters.remove(id)?.complete(unanswered(message))
        }
    }

    @Synchronized
    fun cancelRun(runId: String) {
        mutable.value.filter { it.runId == runId && it.active }.forEach { dismiss(it.id, "原任务已停止，未回答不代表同意") }
    }

    private fun summary(form: UserQuestionSet, drafts: List<QuestionDraft>): String =
        form.questions.indices.joinToString(" · ") { index ->
            val q = form.questions[index]; val d = drafts[index]
            val text = if (d.other.isNotBlank()) listOf(d.other) else emptyList()
            q.tab + "：" + (d.selected.sorted().map { q.options[it].label } + text).joinToString("、")
        }

    private fun answers(form: UserQuestionSet): String = JSONObject().put("ok", true).put("status", "answered")
        .put("summary", form.summary).put("answers", JSONArray().also { list ->
            form.questions.forEachIndexed { index, q ->
                val draft = form.drafts[index]
                list.put(JSONObject().put("question", q.prompt).put("question_id", index)
                    .put("selected", JSONArray(draft.selected.sorted().map { q.options[it].label }))
                    .put("other", draft.other))
            }
        }).toString()
    fun unanswered(message: String) = JSONObject().put("ok", false).put("status", "unanswered")
        .put("code", "USER_QUESTION_UNANSWERED").put("message", "$message；不得把未回答当成授权或同意").toString()
}

internal class UserQuestionHost(private val context: Context, private val conversationId: String?,
    private val runId: String, private val controller: AgentRunController) {
    private val owned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun ask(callId: String, args: JSONObject): String {
        if (conversationId.isNullOrBlank()) return UserQuestionStore.unanswered("当前入口没有可回答的聊天会话，请回到会话后再问")
        UserQuestionStore.initialize(context)
        controller.throwIfCancelled()
        val (id, waiter) = UserQuestionStore.open(conversationId, runId, callId, args)
        owned += id
        val registration = controller.register { UserQuestionStore.dismiss(id, "原任务已停止") }
        return try {
            val result = waiter.get()
            controller.throwIfCancelled()
            result
        } catch (failure: InterruptedException) {
            UserQuestionStore.dismiss(id, "等待回答已中断")
            Thread.currentThread().interrupt()
            controller.throwIfCancelled()
            UserQuestionStore.unanswered("等待回答已中断")
        } finally { registration.close(); owned.remove(id) }
    }
    fun cancelPending() { owned.toList().forEach { UserQuestionStore.dismiss(it, "原任务已结束") } }
}
