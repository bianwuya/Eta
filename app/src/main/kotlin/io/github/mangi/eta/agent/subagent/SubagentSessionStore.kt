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
    )

    // Identity stays process-local; it is never written to display snapshots or model inputs.
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
        val id = "sub_${bucket.sequence.incrementAndGet()}"
        val entry = Entry(subagentId = id, profile = profile, running = AtomicBoolean(true))
        bucket.entries[id] = entry
        entry
    }

    fun get(conversationKey: String, subagentId: String): Entry? = synchronized(lock) {
        buckets[conversationKey]?.entries?.get(subagentId)
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
