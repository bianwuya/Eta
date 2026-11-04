package io.github.mangi.eta.local.subagentstats

import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic metadata only. No request/response text, headers or credentials are retained. */
@Serializable
internal data class SubagentRequestStat(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val subagentId: String,
    val invocationId: String,
    val parentRunId: String,
    val profile: String,
    val providerId: String,
    val model: String,
    val purpose: String,
    val ordinal: Int,
    val resumed: Boolean = false,
    val contextSummaryCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val elapsedMs: Long = 0,
    val status: String = "running",
    val errorCode: String? = null,
    val inputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val outputTokens: Int? = null,
    val previousInputTokens: Int? = null,
    val httpAttempts: Int = 0,
    val httpCode: Int? = null,
    val requestId: String? = null,
    val host: String = "",
    val path: String = "",
    val requestBytes: Long? = null,
    val messageCount: Int? = null,
    val previousMessageCount: Int? = null,
    val previousRequestBytes: Long? = null,
    val commonPrefixMessages: Int? = null,
    val historyChange: String = "unavailable",
    val changedFields: List<String> = emptyList(),
    val bodyHash: String = "",
    val systemHash: String = "",
    val toolsHash: String = "",
    val parametersHash: String = "",
    val cacheKeyHash: String = "",
    val baselineId: String? = null,
)

internal data class SubagentWireFingerprint(
    val bytes: Long,
    val messages: List<String>,
    val body: String,
    val system: String,
    val tools: String,
    val parameters: String,
    val cacheKey: String,
    val host: String,
    val path: String,
) {
    companion object {
        fun read(request: Request): SubagentWireFingerprint? {
            val body = request.body ?: return null
            if (body.isOneShot() || body.isDuplex()) return null
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.size
            val raw = buffer.readUtf8()
            val json = JSONObject(raw)
            val messages = json.optJSONArray("messages") ?: json.optJSONArray("input")
                ?: json.opt("input")?.let { JSONArray().put(it) } ?: return null
            val systems = JSONArray()
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                if (message.optString("role") in setOf("system", "developer")) systems.put(message)
            }
            val instructions = JSONObject().put("messages", systems)
            listOf("system", "instructions", "system_instruction").forEach { key ->
                json.opt(key)?.let { instructions.put(key, it) }
            }
            val parameters = JSONObject()
            json.keys().forEach { key ->
                if (key !in setOf("messages", "input", "system", "instructions", "system_instruction", "tools", "prompt_cache_key")) {
                    parameters.put(key, json.get(key))
                }
            }
            return SubagentWireFingerprint(
                bytes = bytes,
                messages = (0 until messages.length()).map { hash(canonical(messages.get(it))) },
                body = hash(raw),
                system = hash(canonical(instructions)),
                tools = hash(canonical(json.opt("tools"))),
                parameters = hash(canonical(parameters)),
                cacheKey = hash(canonical(json.opt("prompt_cache_key"))),
                host = request.url.host,
                path = request.url.encodedPath,
            )
        }

        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }

        private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Baseline is scoped to one child. It also survives resume while that child is in memory. */
internal class SubagentRequestBaseline {
    private val previous = mutableMapOf<String, Pair<SubagentRequestStat, SubagentWireFingerprint>>()

    @Synchronized
    fun compare(record: SubagentRequestStat, wire: SubagentWireFingerprint): SubagentRequestStat {
        val prior = previous[record.purpose]
        val old = prior?.second
        val common = old?.messages?.zip(wire.messages)?.takeWhile { it.first == it.second }?.size
        val changes = if (old == null) emptyList() else buildList {
            if (old.system != wire.system) add("system")
            if (old.tools != wire.tools) add("tools")
            if (old.parameters != wire.parameters) add("parameters")
            if (old.cacheKey != wire.cacheKey) add("cache_key")
            if (old.host != wire.host || old.path != wire.path) add("endpoint")
        }
        val comparison = when {
            old == null -> "first"
            common == old.messages.size && wire.messages.size == old.messages.size -> "identical"
            common == old.messages.size -> "append"
            wire.messages.size < old.messages.size -> "shorter"
            else -> "prefix_changed"
        }
        val updated = record.copy(
            requestBytes = wire.bytes, messageCount = wire.messages.size,
            previousMessageCount = old?.messages?.size, previousRequestBytes = old?.bytes,
            commonPrefixMessages = common, historyChange = comparison, changedFields = changes,
            bodyHash = wire.body, systemHash = wire.system, toolsHash = wire.tools,
            parametersHash = wire.parameters, cacheKeyHash = wire.cacheKey,
            baselineId = prior?.first?.id, previousInputTokens = prior?.first?.inputTokens,
            host = wire.host, path = wire.path,
        )
        previous[record.purpose] = updated to wire
        return updated
    }

    @Synchronized
    fun finish(record: SubagentRequestStat) {
        val current = previous[record.purpose] ?: return
        if (current.first.id == record.id) previous[record.purpose] = record to current.second
    }
}

/** Captures the actual serialized HTTP request in both Release and Debug, without changing it. */
internal object SubagentRequestStats {
    private class Attempt(
        var record: SubagentRequestStat,
        val baseline: SubagentRequestBaseline,
        private val save: (SubagentRequestStat) -> Unit,
    ) {
        fun persist() { runCatching { save(record) } }
        fun request(request: Request) {
            record = record.copy(httpAttempts = record.httpAttempts + 1)
            // A subscription 401 refresh may send the same body twice. Preserve the prior
            // model-attempt comparison rather than comparing this attempt to itself.
            SubagentWireFingerprint.read(request)?.let { wire ->
                if (record.httpAttempts == 1) record = baseline.compare(record, wire)
            }
            persist()
        }
    }
    private val active = ThreadLocal<Attempt>()

    val interceptor = Interceptor { chain ->
        val attempt = active.get()
        // Diagnostics must never change model execution, including an unrecognized body format.
        runCatching { attempt?.request(chain.request()) }
        val response = chain.proceed(chain.request())
        attempt?.let {
            it.record = it.record.copy(
                httpCode = response.code,
                requestId = (response.header("x-request-id") ?: response.header("request-id"))?.take(200),
            )
        }
        response
    }

    fun provider(
        delegate: AgentProviderClient,
        template: SubagentRequestStat,
        baseline: SubagentRequestBaseline,
        save: (SubagentRequestStat) -> Unit,
    ): AgentProviderClient = object : AgentProviderClient {
        override val id = delegate.id
        override val capabilities = delegate.capabilities
        private var ordinal = 0
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            val attempt = Attempt(template.copy(
                id = UUID.randomUUID().toString(), model = request.effectiveConfig.model,
                providerId = request.effectiveConfig.providerId, purpose = request.purpose.name,
                ordinal = ++ordinal, startedAt = System.currentTimeMillis(),
                contextSummaryCount = (0 until request.messages.length()).count {
                    request.messages.optJSONObject(it)?.optBoolean("_eta_context_summary") == true
                },
            ), baseline, save)
            val previous = active.get()
            active.set(attempt)
            val started = System.nanoTime()
            attempt.persist()
            try {
                val response = delegate.complete(request, runController) { event ->
                    if (event is ProviderEvent.Usage) {
                        val usage = event.usage
                        attempt.record = attempt.record.copy(
                            inputTokens = usage.inputTokens ?: attempt.record.inputTokens,
                            cachedTokens = usage.cachedTokens ?: attempt.record.cachedTokens,
                            outputTokens = usage.outputTokens ?: attempt.record.outputTokens,
                        )
                    }
                    onEvent(event)
                }
                attempt.record = attempt.record.copy(status = "completed")
                return response
            } catch (failure: Throwable) {
                attempt.record = attempt.record.copy(
                    status = if (runController.isCancelled) "cancelled" else "failed",
                    errorCode = (failure as? AgentModelFailure)?.code ?: failure.javaClass.simpleName,
                )
                throw failure
            } finally {
                attempt.record = attempt.record.copy(elapsedMs = (System.nanoTime() - started) / 1_000_000)
                baseline.finish(attempt.record)
                attempt.persist()
                if (previous == null) active.remove() else active.set(previous)
            }
        }
    }
}

internal data class SubagentCacheTotals(
    val requests: Int,
    val knownPairs: Int,
    val missingCache: Int,
    val zeroCache: Int,
    val input: Long,
    val cached: Long,
) {
    val ratio: Double? get() = if (input > 0) cached.toDouble() / input else null
    companion object {
        fun from(records: List<SubagentRequestStat>): SubagentCacheTotals {
            val known = records.filter { it.inputTokens != null && it.cachedTokens != null &&
                it.inputTokens >= 0 && it.cachedTokens in 0..it.inputTokens }
            return SubagentCacheTotals(records.size, known.size, records.count { it.cachedTokens == null },
                records.count { it.cachedTokens == 0 }, known.sumOf { it.inputTokens!!.toLong() },
                known.sumOf { it.cachedTokens!!.toLong() })
        }
    }
}
