package io.github.mangi.eta.local.conversationcommunication

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * QUEUED 等下次运行补投；SENDING 表示已开始投递、结果未确认，永远不自动重投；
 * DELIVERED/FAILED 为终态。未知状态按 SENDING 处理，宁可排队也不重复投递。
 */
internal enum class ConversationDeliveryState { QUEUED, SENDING, DELIVERED, FAILED }

/** 会话最近一次运行的终态；用于判断能否被跨会话消息唤醒。 */
internal enum class ConversationTerminalOutcome { COMPLETED, FAILED, STOPPED, PAUSED }

internal data class ConversationOutcomeRecord(
    val conversationId: String,
    val outcome: ConversationTerminalOutcome,
    val runId: String,
    val at: Long,
)

internal sealed interface ConversationOutboxRead<out T> {
    data class Known<T>(val value: T) : ConversationOutboxRead<T>
    data class Unavailable(val reason: String) : ConversationOutboxRead<Nothing>
}

internal sealed interface ConversationOutboxWrite {
    data class Stored(val entry: ConversationOutboxEntry?) : ConversationOutboxWrite
    data class Rejected(val code: String, val message: String) : ConversationOutboxWrite
    data class Failed(val message: String) : ConversationOutboxWrite
}

/**
 * 跨会话消息的投递记录。message_id 同时是幂等键：
 * 同一个 message_id 重复发送只投递一次，返回第一次的真实投递语义。
 */
internal data class ConversationOutboxEntry(
    val messageId: String,
    val sourceConversationId: String,
    val sourceTitle: String,
    val sourceRunId: String,
    val targetConversationId: String,
    val text: String,
    val createdAt: Long,
    val state: ConversationDeliveryState,
    val delivery: String,
    val runId: String = "",
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("message_id", messageId)
        .put("source_conversation_id", sourceConversationId)
        .put("source_title", sourceTitle)
        .put("source_run_id", sourceRunId)
        .put("target_conversation_id", targetConversationId)
        .put("text", text)
        .put("created_at", createdAt)
        .put("state", state.name)
        .put("delivery", delivery)
        .put("run_id", runId)
        .put("note", note)

    fun envelope(): ConversationMessageEnvelope = ConversationMessageEnvelope(
        messageId = messageId,
        sourceConversationId = sourceConversationId,
        sourceTitle = sourceTitle,
        sourceRunId = sourceRunId,
        targetConversationId = targetConversationId,
        text = text,
        createdAt = createdAt,
    )

    companion object {
        fun fromJson(json: JSONObject): ConversationOutboxEntry? {
            val messageId = json.optString("message_id")
            val target = json.optString("target_conversation_id")
            val text = json.optString("text")
            if (messageId.isBlank() || target.isBlank() || text.isBlank()) return null
            return ConversationOutboxEntry(
                messageId = messageId,
                sourceConversationId = json.optString("source_conversation_id"),
                sourceTitle = json.optString("source_title"),
                sourceRunId = json.optString("source_run_id"),
                targetConversationId = target,
                text = text,
                createdAt = json.optLong("created_at"),
                state = runCatching {
                    ConversationDeliveryState.valueOf(json.optString("state"))
                }.getOrDefault(ConversationDeliveryState.SENDING),
                delivery = json.optString("delivery"),
                runId = json.optString("run_id"),
                note = json.optString("note"),
            )
        }
    }
}

internal interface ConversationMessageOutbox {
    /** 同一 outbox 实例共享的事务边界；send 的“校验→预留→投递→更新”必须整体持有。 */
    fun <T> withLock(block: () -> T): T

    fun find(messageId: String): ConversationOutboxRead<ConversationOutboxEntry?>
    fun pendingFor(targetConversationId: String): ConversationOutboxRead<List<ConversationOutboxEntry>>
    fun outcome(conversationId: String): ConversationOutboxRead<ConversationOutcomeRecord?>
    fun record(entry: ConversationOutboxEntry): ConversationOutboxWrite
    fun update(entry: ConversationOutboxEntry): ConversationOutboxWrite
    fun remove(messageId: String): ConversationOutboxWrite
    fun recordOutcome(record: ConversationOutcomeRecord): ConversationOutboxWrite
    fun clearOutcome(conversationId: String): ConversationOutboxWrite
}

/**
 * 有界、原子写入的本地投递记录（消息 + 会话终态共用同一文件与同一事务）。
 *
 * 只有写盘成功才更新内存并返回 Stored；读取失败或文件损坏返回 Unavailable，
 * 调用方必须停止自动唤醒而不是当作“空记录”。不提供后台常驻泵或无限重试。
 */
internal class ConversationMessageFileOutbox(private val file: File) : ConversationMessageOutbox {
    private val lock = Any()
    private var snapshot: Snapshot? = null

    private data class Snapshot(
        val entries: List<ConversationOutboxEntry>,
        val outcomes: Map<String, ConversationOutcomeRecord>,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty() && outcomes.isEmpty()
    }

    override fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    private sealed interface Load {
        data class Loaded(val snapshot: Snapshot) : Load
        data object Missing : Load
        data class Corrupt(val reason: String) : Load
    }

    override fun find(messageId: String): ConversationOutboxRead<ConversationOutboxEntry?> = synchronized(lock) {
        when (val load = load()) {
            is Load.Corrupt -> ConversationOutboxRead.Unavailable(load.reason)
            is Load.Loaded -> ConversationOutboxRead.Known(load.snapshot.entries.firstOrNull { it.messageId == messageId })
            Load.Missing -> ConversationOutboxRead.Known(null)
        }
    }

    override fun pendingFor(targetConversationId: String): ConversationOutboxRead<List<ConversationOutboxEntry>> =
        synchronized(lock) {
            when (val load = load()) {
                is Load.Corrupt -> ConversationOutboxRead.Unavailable(load.reason)
                is Load.Loaded -> ConversationOutboxRead.Known(
                    load.snapshot.entries.filter {
                        it.targetConversationId == targetConversationId &&
                            it.state == ConversationDeliveryState.QUEUED
                    },
                )
                Load.Missing -> ConversationOutboxRead.Known(emptyList())
            }
        }

    override fun outcome(conversationId: String): ConversationOutboxRead<ConversationOutcomeRecord?> = synchronized(lock) {
        when (val load = load()) {
            is Load.Corrupt -> ConversationOutboxRead.Unavailable(load.reason)
            is Load.Loaded -> ConversationOutboxRead.Known(load.snapshot.outcomes[conversationId])
            Load.Missing -> ConversationOutboxRead.Known(null)
        }
    }

    /** 已存在同一 message_id 时保留第一条，保证幂等且不重复投递。 */
    override fun record(entry: ConversationOutboxEntry): ConversationOutboxWrite = mutate { current ->
        val existing = current.entries.firstOrNull { it.messageId == entry.messageId }
        if (existing != null) return@mutate Mutation(current, ConversationOutboxWrite.Stored(existing))
        if (current.entries.size >= MAX_ENTRIES) {
            val evictable = current.entries
                .filter {
                    it.state == ConversationDeliveryState.DELIVERED ||
                        it.state == ConversationDeliveryState.FAILED
                }
                .minByOrNull { it.createdAt }
                ?: return@mutate Mutation(
                    current,
                    ConversationOutboxWrite.Rejected(
                        code = OUTBOX_FULL,
                        message = "待投递的跨会话消息已达上限（$MAX_ENTRIES），本次未发送；请等目标会话运行后再试",
                    ),
                )
            val entries = current.entries - evictable
            Mutation(current.copy(entries = entries), ConversationOutboxWrite.Stored(entry))
        } else {
            Mutation(current.copy(entries = current.entries + entry), ConversationOutboxWrite.Stored(entry))
        }
    }

    override fun update(entry: ConversationOutboxEntry): ConversationOutboxWrite = mutate { current ->
        if (current.entries.none { it.messageId == entry.messageId }) {
            return@mutate Mutation(
                current,
                ConversationOutboxWrite.Rejected(MESSAGE_NOT_FOUND, "投递记录已不存在，本次回执未持久化"),
            )
        }
        Mutation(
            current.copy(entries = current.entries.map { if (it.messageId == entry.messageId) entry else it }),
            ConversationOutboxWrite.Stored(entry),
        )
    }

    override fun remove(messageId: String): ConversationOutboxWrite = mutate { current ->
        Mutation(
            current.copy(entries = current.entries.filterNot { it.messageId == messageId }),
            ConversationOutboxWrite.Stored(null),
        )
    }

    /** 终态记录有界；被淘汰的会话退化为“未知”，策略上只会排队而不会自动唤醒。 */
    override fun recordOutcome(record: ConversationOutcomeRecord): ConversationOutboxWrite = mutate { current ->
        val outcomes = current.outcomes.toMutableMap()
        outcomes[record.conversationId] = record
        if (outcomes.size > MAX_OUTCOMES) {
            outcomes.entries
                .sortedBy { it.value.at }
                .firstOrNull { it.key != record.conversationId }
                ?.let { outcomes.remove(it.key) }
        }
        Mutation(current.copy(outcomes = outcomes), ConversationOutboxWrite.Stored(null))
    }

    override fun clearOutcome(conversationId: String): ConversationOutboxWrite = mutate { current ->
        Mutation(current.copy(outcomes = current.outcomes - conversationId), ConversationOutboxWrite.Stored(null))
    }

    private fun load(): Load {
        snapshot?.let { return Load.Loaded(it) }
        if (!file.exists()) return Load.Missing
        if (!file.isFile) return Load.Corrupt("投递记录不是普通文件")
        return try {
            val root = JSONObject(file.readText())
            val entries = mutableListOf<ConversationOutboxEntry>()
            val array = root.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index)?.let(ConversationOutboxEntry::fromJson)
                    ?: return Load.Corrupt("投递记录第 $index 条无效")
                entries += entry
            }
            val outcomes = mutableMapOf<String, ConversationOutcomeRecord>()
            root.optJSONObject(KEY_OUTCOMES)?.let { json ->
                json.keys().forEach { id ->
                    val record = json.optJSONObject(id)?.let { outcomeFromJson(id, it) }
                        ?: return Load.Corrupt("会话终态记录无效：$id")
                    outcomes[id] = record
                }
            }
            val loaded = Snapshot(entries, outcomes)
            snapshot = loaded
            Load.Loaded(loaded)
        } catch (error: Exception) {
            // 损坏不当作空：否则停止标记丢失会让已停止的会话被自动唤醒。
            Load.Corrupt("投递记录读取失败（${error.javaClass.simpleName}）")
        }
    }

    private class Mutation(val next: Snapshot, val result: ConversationOutboxWrite)

    private fun mutate(block: (Snapshot) -> Mutation): ConversationOutboxWrite = synchronized(lock) {
        val current = when (val load = load()) {
            is Load.Corrupt -> return ConversationOutboxWrite.Failed(load.reason)
            is Load.Loaded -> load.snapshot
            Load.Missing -> Snapshot(emptyList(), emptyMap())
        }
        val mutation = block(current)
        if (mutation.next == current) return mutation.result
        when (val written = persist(mutation.next)) {
            is ConversationOutboxWrite.Stored -> {
                snapshot = mutation.next
                mutation.result
            }
            else -> written
        }
    }

    private fun persist(next: Snapshot): ConversationOutboxWrite {
        val parent = file.parentFile ?: return ConversationOutboxWrite.Failed("投递记录目录不可用")
        val payload = JSONObject()
            .put("version", VERSION)
            .put(KEY_ENTRIES, JSONArray().also { array -> next.entries.forEach { array.put(it.toJson()) } })
            .put(KEY_OUTCOMES, JSONObject().also { json ->
                next.outcomes.forEach { (id, record) -> json.put(id, outcomeToJson(record)) }
            })
            .toString()
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            FileOutputStream(temporary).use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            // 同目录 rename 是原子替换；失败不回退为直接覆盖，避免半截文件。
            if (!temporary.renameTo(file)) {
                temporary.delete()
                ConversationOutboxWrite.Failed("投递记录替换失败，本次未生效")
            } else {
                ConversationOutboxWrite.Stored(null)
            }
        } catch (error: Exception) {
            runCatching { temporary.delete() }
            ConversationOutboxWrite.Failed("投递记录写入失败（${error.javaClass.simpleName}）")
        }
    }

    private companion object {
        const val VERSION = 2
        const val KEY_ENTRIES = "entries"
        const val KEY_OUTCOMES = "conversation_outcomes"
        const val MAX_ENTRIES = 200
        const val MAX_OUTCOMES = 100
        const val OUTBOX_FULL = "CONVERSATION_OUTBOX_FULL"
        const val MESSAGE_NOT_FOUND = "CONVERSATION_MESSAGE_NOT_FOUND"

        fun outcomeToJson(record: ConversationOutcomeRecord): JSONObject = JSONObject()
            .put("outcome", record.outcome.name)
            .put("run_id", record.runId)
            .put("at", record.at)

        fun outcomeFromJson(conversationId: String, json: JSONObject): ConversationOutcomeRecord? {
            val outcome = runCatching {
                ConversationTerminalOutcome.valueOf(json.optString("outcome"))
            }.getOrNull() ?: return null
            return ConversationOutcomeRecord(
                conversationId = conversationId,
                outcome = outcome,
                runId = json.optString("run_id"),
                at = json.optLong("at"),
            )
        }
    }
}
