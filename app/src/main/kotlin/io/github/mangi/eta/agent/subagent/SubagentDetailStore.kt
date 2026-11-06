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

    private data class HistoryState(
        val entries: MutableList<SubagentHistoryEntry>,
        val lengths: MutableMap<String, Int>,
    )
    private val historyStates = mutableMapOf<String, HistoryState>()

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

    /** One transaction per visible event boundary, never one per streaming delta. */
    @Synchronized
    fun recordHistory(id: String, entry: SubagentHistoryEntry, body: String, terminal: Boolean = false) = runBlocking(Dispatchers.IO) {
        try {
            db.withTransaction {
                val state = historyStates[id] ?: run {
                    val entries = history(id).toMutableList()
                    val lengths = mutableMapOf<String, Int>()
                    for (item in entries) {
                        lengths[item.id] = dao.textChunks(TABLE, id, "history-${item.id}", HISTORY_MAX_BODY_PAGES, 0)
                            .sumOf { it.content.length }
                    }
                    HistoryState(entries, lengths).also { historyStates[id] = it }
                }
                val existing = state.entries.indexOfFirst { it.id == entry.id }
                if (existing < 0 && (state.entries.count { it.id != HISTORY_LIMIT_ID && it.kind != "terminal" } >= MAX_HISTORY_ENTRIES && !terminal ||
                        state.entries.count { it.kind == "terminal" } >= MAX_TERMINAL_ENTRIES && terminal)) {
                    if (state.entries.none { it.id == HISTORY_LIMIT_ID }) {
                        val marker = SubagentHistoryEntry(HISTORY_LIMIT_ID, "历史保留上限", "limit", "truncated")
                        state.entries.add(marker)
                        put(id, "history-$HISTORY_LIMIT_ID", "[达到历史条数上限，后续部分内容未保存；终态仍会记录]")
                    }
                } else {
                    val marker = "\n[达到历史保留上限，后续内容未保存]"
                    val oldLength = state.lengths[entry.id] ?: 0
                    val used = state.lengths.values.sum() - oldLength
                    // The final error/cancellation must remain readable even after the normal budget is exhausted.
                    val available = if (terminal) MAX_TERMINAL_BODY_CHARS else (MAX_HISTORY_CHARS - used).coerceAtLeast(0)
                    val safe = SubagentDisplayText.redact(body)
                    val bounded = if (safe.length <= minOf(MAX_FIELD_CHARS, available)) safe else {
                        safe.takeSafe((minOf(MAX_FIELD_CHARS, available) - marker.length).coerceAtLeast(0)) + marker
                    }
                    val safeEntry = entry.copy(
                        title = SubagentDisplayText.redact(entry.title).filter { it >= ' ' }.take(120),
                        kind = entry.kind.take(24), status = entry.status.take(24),
                    )
                    if (existing < 0) state.entries.add(safeEntry) else state.entries[existing] = safeEntry
                    state.lengths[entry.id] = bounded.length
                    put(id, "history-${entry.id}", bounded)
                }
                put(id, "history", Json.encodeToString(state.entries))
                put(id, "_updated", System.currentTimeMillis().toString())
            }
        } catch (failure: Throwable) {
            // put() updates write caches inside the transaction: invalidate them on rollback.
            invalidateCaches(id)
            throw failure
        }
    }

    /** Mark already closed text blocks as partial if that model attempt later failed. */
    @Synchronized
    fun markHistoryStatus(id: String, entryIds: List<String>, status: String) = runBlocking(Dispatchers.IO) {
        if (entryIds.isEmpty()) return@runBlocking
        try {
            db.withTransaction {
                val state = historyStates[id] ?: return@withTransaction
                val selected = entryIds.toSet()
                for (index in state.entries.indices) {
                    val entry = state.entries[index]
                    if (entry.id in selected) state.entries[index] = entry.copy(status = status)
                }
                put(id, "history", Json.encodeToString(state.entries))
            }
        } catch (failure: Throwable) {
            invalidateCaches(id)
            throw failure
        }
    }

    suspend fun history(id: String): List<SubagentHistoryEntry> {
        val chunks = dao.textChunks(TABLE, id, "history", HISTORY_MAX_INDEX_PAGES, 0)
        if (chunks.isEmpty()) return emptyList()
        return Json.decodeFromString(chunks.joinToString("") { it.content })
    }

    private suspend fun put(id: String, field: String, raw: String) {
        val key = id to field
        val text = if (field == "history") raw else if (raw.length > MAX_FIELD_CHARS) raw.take(MAX_FIELD_CHARS) + "\n[达到单项保留上限，后续内容未保存]" else raw
        if (cached[key] == text) return
        val oldLength = lengths[key] ?: 0
        if (field.startsWith("step-") && writtenChars - oldLength + text.length > MAX_OWNER_CHARS) { full = true; return }
        dao.deleteTextChunks(TABLE, id, field)
        text.chunkedSafe(PAGE_CHARS).ifEmpty { listOf("") }.forEachIndexed { index, part ->
            dao.insertTextChunk(AgentTextChunkEntity(TABLE, id, field, index, part))
        }
        // History has its own budget; do not consume the legacy step-capture allowance twice.
        if (field != "history" && !field.startsWith("history-")) writtenChars += text.length - oldLength
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
    suspend fun clear(id: String) {
        dao.deleteLocalTextOwner(TABLE, id)
        synchronized(this) { invalidateCaches(id) }
    }
    private fun invalidateCaches(id: String) {
        val legacyChars = lengths.filterKeys { it.first == id && it.second != "history" && !it.second.startsWith("history-") }
            .values.sumOf { it.toLong() }
        writtenChars = (writtenChars - legacyChars).coerceAtLeast(0)
        historyStates.remove(id)
        cached.keys.removeAll { it.first == id }
        lengths.keys.removeAll { it.first == id }
        captured.removeAll { it.first == id }
    }
    private fun String.takeSafe(limit: Int): String {
        var end = minOf(limit, length)
        if (end in 1 until length && this[end - 1].isHighSurrogate()) end--
        return substring(0, end)
    }
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
        const val MAX_HISTORY_ENTRIES = 256
        const val MAX_HISTORY_CHARS = 500_000
        private const val MAX_TERMINAL_BODY_CHARS = 2_000
        private const val MAX_TERMINAL_ENTRIES = 1
        private const val HISTORY_LIMIT_ID = "limit"
        private const val HISTORY_MAX_BODY_PAGES = MAX_FIELD_CHARS / PAGE_CHARS + 1
        // 256 JSON-escaped titles can exceed MAX_FIELD_CHARS even though each title is capped.
        private const val HISTORY_MAX_INDEX_PAGES = 64
        private const val MAX_TASKS = 64
        private const val MAX_OWNER_CHARS = 500_000
        private const val MAX_TOTAL_CHARS = 8_000_000
    }
}
