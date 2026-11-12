package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话内 Subagent 实例上下文缓存，供主 Agent 通过 `resume` 续接已有的 Subagent。
 */
internal object SubagentSessionStore {
    private const val MAX_CONVERSATIONS = 16
    private const val MAX_AGENTS_PER_CONVERSATION = 16
    private var directory: java.io.File? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @kotlinx.serialization.Serializable
    private data class Saved(val id: String, val profile: SubagentProfile,
        val history: List<AgentModelClient.ConversationMessage>, val modelIdentity: String?,
        val cancelled: Boolean, val compactions: Int, val context: ContextState?, val completed: Boolean = false)

    fun initialize(context: android.content.Context) = synchronized(lock) {
        directory = java.io.File(context.filesDir, "subagent-sessions-v1").also { check(it.mkdirs() || it.isDirectory) }
    }

    private fun file(conversationKey: String, id: String): java.io.File? = directory?.let {
        val owner = java.security.MessageDigest.getInstance("SHA-256").digest(conversationKey.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        require(id.matches(Regex("sub_[A-Za-z0-9_-]+")))
        java.io.File(it, "$owner-$id.json")
    }

    fun modelIdentity(config: AgentModelClient.ModelConfig): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(config.providerId, config.baseUrl, config.model, config.authMode, config.apiKey).joinToString("\n").toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun persist(conversationKey: String, entry: Entry) = synchronized(lock) {
        val target = file(conversationKey, entry.subagentId) ?: return@synchronized
        val safeHistory = io.github.mangi.eta.agent.model.AgentConversationCodec.decodeTranscript(
            io.github.mangi.eta.agent.model.AgentConversationCodec.encodeTranscriptForStorage(entry.history))
        val saved = Saved(entry.subagentId, entry.profile, safeHistory, entry.modelIdentity,
            entry.explicitlyCancelled.get(), entry.compactionCount.get(), entry.lastContext, entry.completed)
        val bytes = json.encodeToString(kotlinx.serialization.serializer<Saved>(), saved).toByteArray()
        val atomic = android.util.AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    internal fun clearMemoryForTest() = synchronized(lock) { buckets.clear() }

    class Entry(
        val subagentId: String,
        val profile: SubagentProfile,
        @Volatile var history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val running: AtomicBoolean = AtomicBoolean(false),
        /** Explicit parent cancellation is terminal; a later resume must not resurrect stale work. */
        val explicitlyCancelled: AtomicBoolean = AtomicBoolean(false),
        // A live entry starts at zero and keeps the count across resume invocations.
        // The session store is process-local; archived snapshots have their own value.
        val compactionCount: AtomicInteger = AtomicInteger(0),
        // Process-local resume only. The serialized activity snapshot holds the archived copy.
        @Volatile var lastContext: ContextState? = null,
        val requestStatsBaseline: io.github.mangi.eta.local.subagentstats.SubagentRequestBaseline =
            io.github.mangi.eta.local.subagentstats.SubagentRequestBaseline(),
        @Volatile var modelIdentity: String? = null,
        @Volatile var completed: Boolean = false,
    )

    // Identity stays process-local; it is never written to display snapshots or model inputs.
    @kotlinx.serialization.Serializable
    data class ContextState(val reading: SubagentContextReading, val model: String, val providerId: String)

    private class ConversationBucket {
        val sequence = AtomicInteger(0)
        val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > MAX_AGENTS_PER_CONVERSATION && eldest?.value?.running?.get() != true
        }
    }

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, ConversationBucket>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ConversationBucket>?,
        ): Boolean = size > MAX_CONVERSATIONS && eldest?.value?.entries?.values?.none { it.running.get() } == true
    }

    fun create(conversationKey: String, profile: SubagentProfile): Entry = synchronized(lock) {
        val bucket = buckets.getOrPut(conversationKey) { ConversationBucket() }
        var id: String
        do { id = "sub_${bucket.sequence.incrementAndGet()}" } while (file(conversationKey, id)?.exists() == true)
        val entry = Entry(subagentId = id, profile = profile, running = AtomicBoolean(true))
        bucket.entries[id] = entry
        entry
    }

    fun get(conversationKey: String, subagentId: String): Entry? = synchronized(lock) {
        buckets[conversationKey]?.entries?.get(subagentId) ?: file(conversationKey, subagentId)?.takeIf { it.exists() }?.let { target ->
            val saved = json.decodeFromString<Saved>(android.util.AtomicFile(target).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            Entry(saved.id, saved.profile, saved.history, explicitlyCancelled = AtomicBoolean(saved.cancelled),
                compactionCount = AtomicInteger(saved.compactions), lastContext = saved.context, modelIdentity = saved.modelIdentity,
                completed = saved.completed)
                .also { buckets.getOrPut(conversationKey) { ConversationBucket() }.entries[subagentId] = it }
        }
    }

    fun complete(
        conversationKey: String,
        subagentId: String,
        updatedHistory: List<AgentModelClient.ConversationMessage>,
    ) {
        synchronized(lock) {
            val entry = buckets[conversationKey]?.entries?.get(subagentId) ?: return
            entry.history = updatedHistory
            entry.running.set(false)
        }
    }

    fun releaseRunning(conversationKey: String, subagentId: String) {
        synchronized(lock) {
            buckets[conversationKey]?.entries?.get(subagentId)?.running?.set(false)
        }
    }
}
