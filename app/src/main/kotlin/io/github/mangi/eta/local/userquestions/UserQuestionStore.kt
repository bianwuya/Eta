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

    @Synchronized
    fun initialize(context: Context) {
        if (file != null) return
        val storage = AtomicFile(File(context.filesDir, "userquestions/drafts.json"))
        val loaded = if (storage.baseFile.exists()) {
            require(storage.baseFile.length() <= 524288) { "提问草稿文件过大" }
            json.decodeFromString<List<UserQuestionSet>>(storage.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
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
        try { output.write(bytes); storage.finishWrite(output) }
        catch (failure: Exception) { storage.failWrite(output); throw failure }
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

    @Synchronized
    fun draft(id: String, index: Int, value: QuestionDraft) {
        val form = mutable.value.firstOrNull { it.id == id } ?: return
        if (!form.active || form.status != "waiting" || index !in form.questions.indices) return
        val q = form.questions[index]
        require(value.selected.all { it in q.options.indices } && value.other.length <= 2000 && (!value.otherSelected || q.allowOther))
        val drafts = form.drafts.toMutableList().also { it[index] = value }
        publish(mutable.value.map { if (it.id == id) it.copy(drafts = drafts) else it })
    }

    @Synchronized
    fun submit(id: String): Boolean {
        val form = mutable.value.firstOrNull { it.id == id } ?: return false
        val waiter = waiters[id] ?: return false
        if (!form.active || form.status != "waiting" || !form.questions.indices.all { UserQuestionParser.valid(form.questions[it], form.drafts[it]) }) return false
        val summary = form.questions.indices.joinToString(" · ") { index ->
            val q = form.questions[index]; val d = form.drafts[index]
            q.tab + "：" + (d.selected.sorted().map { q.options[it].label } + if (d.otherSelected) listOf(d.other) else emptyList()).joinToString("、")
        }
        val settled = form.copy(active = false, status = "answered", summary = summary)
        publish(mutable.value.map { if (it.id == id) settled else it })
        waiters.remove(id)
        waiter.complete(answers(settled))
        return true
    }

    @Synchronized
    fun dismiss(id: String, message: String = "用户选择稍后回答；这不代表同意") {
        val form = mutable.value.firstOrNull { it.id == id } ?: return
        val waiter = waiters.remove(id)
        val next = mutable.value.map { if (it.id == id && it.active) it.copy(active = false, status = "unanswered") else it }
        try { if (form.active) publish(next) }
        finally { mutable.value = next; waiter?.complete(unanswered(message)) }
    }

    @Synchronized
    fun cancelRun(runId: String) {
        mutable.value.filter { it.runId == runId && it.active }.forEach { dismiss(it.id, "原任务已停止，未回答不代表同意") }
    }

    private fun answers(form: UserQuestionSet): String = JSONObject().put("ok", true).put("status", "answered")
        .put("summary", form.summary).put("answers", JSONArray().also { list ->
            form.questions.forEachIndexed { index, q ->
                val draft = form.drafts[index]
                list.put(JSONObject().put("question", q.prompt).put("question_id", index)
                    .put("selected", JSONArray(draft.selected.sorted().map { q.options[it].label }))
                    .put("other", if (draft.otherSelected) draft.other else ""))
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
