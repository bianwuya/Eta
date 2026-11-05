package io.github.mangi.eta.agent.subagent

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses the existing chunk table. Readers fetch a page, not the complete process transcript. */
internal class SubagentDetailStore(context: Context) {
    private val db = EtaDatabase.get(context.applicationContext)
    private val dao = db.conversationDao()
    private val formatter = AgentTraceFormatter()
    private val cached = mutableMapOf<Pair<String, String>, String>()
    private val captured = mutableSetOf<Pair<String, String>>()
    private val lengths = mutableMapOf<Pair<String, String>, Int>()
    private var full = false
    private var writtenChars = 0L

    @Synchronized
    fun save(id: String, snapshot: SubagentActivitySnapshot) = runBlocking(Dispatchers.IO) {
        db.withTransaction {
            put(id, "header", snapshot.copy(prompt = "", steps = emptyList(), output = "", error = null,
                previewTruncated = snapshot.previewTruncated || full).encode())
            put(id, "prompt", formatter.redactForDisplay(snapshot.prompt))
            put(id, "output", formatter.redactForDisplay(snapshot.output))
            put(id, "error", formatter.redactForDisplay(snapshot.error.orEmpty()))
            put(id, "steps", Json.encodeToString(snapshot.steps.takeLast(MAX_STEPS).map { step ->
                step.copy(title = step.title.filter { it >= ' ' }.take(120), toolName = step.toolName.take(80), detail = "")
            }))
            snapshot.steps.takeLast(MAX_STEPS).forEach { step ->
                if (step.kind != SubagentStepSnapshot.KIND_THINKING && (id to "step-${step.id}") !in captured) {
                    put(id, "step-${step.id}", formatter.redactForDisplay(step.detail) +
                        "\n[此项仅保留原活动预览；完整执行结果未取得]")
                }
            }
            put(id, "_updated", System.currentTimeMillis().toString())
        }
    }

    @Synchronized
    fun capture(id: String, stepId: String, text: String) = runBlocking(Dispatchers.IO) {
        val key = id to "step-$stepId"
        if (captured.size >= MAX_STEPS && key !in captured) { full = true; return@runBlocking }
        db.withTransaction { put(id, key.second, formatter.redactForDisplay(text)) }
        captured += key
    }

    private suspend fun put(id: String, field: String, raw: String) {
        val key = id to field
        val text = if (raw.length > MAX_FIELD_CHARS) raw.take(MAX_FIELD_CHARS) + "\n[达到单项保留上限，后续内容未保存]" else raw
        if (cached[key] == text) return
        val oldLength = lengths[key] ?: 0
        if (field.startsWith("step-") && writtenChars - oldLength + text.length > MAX_OWNER_CHARS) { full = true; return }
        dao.deleteTextChunks(TABLE, id, field)
        text.chunkedSafe(PAGE_CHARS).ifEmpty { listOf("") }.forEachIndexed { index, part ->
            dao.insertTextChunk(AgentTextChunkEntity(TABLE, id, field, index, part))
        }
        writtenChars += text.length - oldLength
        lengths[key] = text.length
        cached[key] = text
    }

    suspend fun header(id: String): SubagentActivitySnapshot? = page(id, "header", 0)?.let(SubagentActivitySnapshot::decode)
    suspend fun steps(id: String): List<SubagentStepSnapshot> {
        val chunks = dao.textChunks(TABLE, id, "steps", 32, 0)
        if (chunks.isEmpty()) return emptyList()
        return Json.decodeFromString(chunks.joinToString("") { it.content })
    }
    suspend fun page(id: String, field: String, index: Int): String? =
        dao.textChunks(TABLE, id, field, 1, index.coerceAtLeast(0)).firstOrNull()?.content
    suspend fun hasNext(id: String, field: String, index: Int): Boolean =
        dao.textChunks(TABLE, id, field, 1, index + 1).isNotEmpty()
    suspend fun prune() {
        var usage = dao.localTextUsage(TABLE)
        var chars = usage.sumOf { it.chars }
        while (usage.size > MAX_TASKS || chars > MAX_TOTAL_CHARS) {
            val first = usage.firstOrNull() ?: break
            dao.deleteLocalTextOwner(TABLE, first.ownerId)
            chars -= first.chars; usage = usage.drop(1)
        }
    }
    suspend fun clear(id: String) = dao.deleteLocalTextOwner(TABLE, id)
    private fun String.chunkedSafe(limit: Int): List<String> = buildList {
        var start = 0
        while (start < length) {
            var end = minOf(start + limit, length)
            if (end < length && this@chunkedSafe[end - 1].isHighSurrogate()) end--
            add(substring(start, end)); start = end
        }
    }
    companion object {
        const val TABLE = "subagent_details_v1"
        const val PAGE_CHARS = 8_000
        const val MAX_FIELD_CHARS = 128_000
        const val MAX_STEPS = 256
        private const val MAX_TASKS = 64
        private const val MAX_OWNER_CHARS = 500_000
        private const val MAX_TOTAL_CHARS = 8_000_000
    }
}
