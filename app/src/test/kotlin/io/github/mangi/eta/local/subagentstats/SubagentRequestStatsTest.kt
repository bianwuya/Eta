package io.github.mangi.eta.local.subagentstats

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelRetry
import io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubagentRequestStatsTest {
    private fun record(child: String = "sub_1") = SubagentRequestStat(
        conversationId = "conv-1", subagentId = child, invocationId = "call-1", parentRunId = "run-1",
        profile = "coder", providerId = "relay", model = "gpt-fixture", purpose = "CHAT", ordinal = 1,
    )
    private fun json(messages: JSONArray = JSONArray().put(JSONObject().put("role", "user").put("content", "PRIVATE_PROMPT"))) =
        JSONObject().put("model", "gpt-fixture").put("messages", messages).put("tools", JSONArray())
            .put("prompt_cache_key", "PRIVATE_CACHE").put("reasoning_effort", "high")
    private fun fingerprint(body: JSONObject): SubagentWireFingerprint = requireNotNull(SubagentWireFingerprint.read(
        Request.Builder().url("https://example.invalid/v1/chat/completions?key=PRIVATE_QUERY")
            .header("Authorization", "Bearer PRIVATE_CREDENTIAL")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
    ))

    @Test
    fun detectsAppendMutationReductionAndEachConfigurationChangeWithoutStoringText() {
        val baseline = SubagentRequestBaseline()
        val first = baseline.compare(record(), fingerprint(json()))
        assertEquals("first", first.historyChange)
        baseline.finish(first.copy(inputTokens = 100))
        val body = json(JSONArray().put(JSONObject().put("content", "PRIVATE_PROMPT").put("role", "user"))
            .put(JSONObject().put("role", "assistant").put("content", "ok")))
        val next = baseline.compare(record(), fingerprint(body))
        assertEquals("append", next.historyChange)
        assertEquals(1, next.commonPrefixMessages)
        assertEquals(100, next.previousInputTokens)
        assertEquals(first.id, next.baselineId)
        assertTrue(next.changedFields.isEmpty())
        body.getJSONArray("messages").getJSONObject(0).put("content", "changed old user")
        body.put("tools", JSONArray().put(JSONObject().put("name", "different")))
        body.put("reasoning_effort", "low").put("prompt_cache_key", "other").put("instructions", "new system")
        val changed = baseline.compare(record(), fingerprint(body))
        assertEquals("prefix_changed", changed.historyChange)
        assertEquals(0, changed.commonPrefixMessages)
        assertEquals(setOf("system", "tools", "parameters", "cache_key"), changed.changedFields.toSet())
        val shorter = baseline.compare(record(), fingerprint(json()))
        assertEquals("shorter", shorter.historyChange)
        assertEquals(2, shorter.previousMessageCount)
        val exported = SubagentStatsStore.export(listOf(first, next, changed, shorter))
        listOf("PRIVATE_PROMPT", "PRIVATE_CACHE", "PRIVATE_CREDENTIAL", "PRIVATE_QUERY", "changed old user", "new system")
            .forEach { assertFalse(exported.contains(it)) }
        assertEquals("/v1/chat/completions", first.path)
    }

    @Test
    fun compressionRequestsDoNotReplaceChatBaselineAndWireHashIsExact() {
        val baseline = SubagentRequestBaseline()
        val chat = baseline.compare(record(), fingerprint(json()))
        baseline.compare(record().copy(purpose = "COMPACTION"), fingerprint(json().put("instructions", "summary")))
        val next = baseline.compare(record(), fingerprint(json()))
        assertEquals(chat.id, next.baselineId)
        assertEquals("identical", next.historyChange)
        val canonicalA = fingerprint(JSONObject("""{"model":"m","messages":[{"role":"user","content":"a"}]}"""))
        val canonicalB = fingerprint(JSONObject("""{"messages":[{"content":"a","role":"user"}],"model":"m"}"""))
        assertEquals(canonicalA.messages, canonicalB.messages)
    }

    @Test
    fun aggregatesTokenWeightedPairsAndKeepsUnknownSeparateFromZero() {
        val total = SubagentCacheTotals.from(listOf(
            record().copy(inputTokens = 100, cachedTokens = 100),
            record().copy(inputTokens = 900, cachedTokens = 0),
            record().copy(inputTokens = 10000, cachedTokens = null),
            record().copy(inputTokens = null, cachedTokens = 8),
        ))
        assertEquals(4, total.requests)
        assertEquals(2, total.knownPairs)
        assertEquals(1, total.missingCache)
        assertEquals(1, total.zeroCache)
        assertEquals(0.1, total.ratio!!, 0.00001)
    }

    @Test
    fun retryUsesIdenticalWireAndUsageDoesNotLeakBetweenAttemptsOrChildren() {
        val received = mutableListOf<String>()
        var calls = 0
        withServer { server, url ->
            server.createContext("/v1/chat/completions") { exchange ->
                received += exchange.requestBody.bufferedReader().readText()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.responseHeaders.set("x-request-id", "fixture-request-${++calls}")
                exchange.sendResponseHeaders(200, 0)
                val body = if (calls == 1) "data: {\"choices\":[" else completeSse(100, if (calls == 2) 0 else null)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val records = linkedMapOf<String, SubagentRequestStat>()
            val wrapper = SubagentRequestStats.provider(OpenAiChatCompletionsProvider, record(),
                SubagentRequestBaseline()) { records[it.id] = it }
            val request = providerRequest(url)
            AgentModelRetry { _, _ -> }.complete(1, request, wrapper, AgentRunController(), {}, { _, _ -> }, {})
            wrapper.complete(request, AgentRunController()) {}
            assertEquals(3, records.size)
            val rows = records.values.toList()
            assertEquals("failed", rows[0].status)
            assertEquals("STREAM_INCOMPLETE", rows[0].errorCode)
            assertNull(rows[0].cachedTokens)
            assertEquals("identical", rows[1].historyChange)
            assertEquals(0, rows[1].cachedTokens)
            assertNull(rows[2].cachedTokens)
            assertEquals(100, rows[1].inputTokens)
            assertEquals("fixture-request-2", rows[1].requestId)
            assertEquals(200, rows[1].httpCode)
            assertEquals(1, rows[1].httpAttempts)
            assertEquals(received[0], received[1])
            assertTrue(rows.all { it.requestBytes != null && it.bodyHash.isNotBlank() })
            val body = JSONObject(received[0])
            assertEquals("PRIVATE_PROMPT", body.getJSONArray("messages").getJSONObject(0).getString("content"))
            // The diagnostic scope is removed when the child request returns.
            OpenAiChatCompletionsProvider.complete(request, AgentRunController()) {}
            assertEquals(3, records.size)
        }
    }

    @Test
    fun parallelChildrenKeepIndependentHistoryAndCounters() {
        withServer { server, url ->
            server.createContext("/v1/chat/completions") { exchange ->
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(completeSse(20, 10).toByteArray()) }
            }
            val records = java.util.Collections.synchronizedList(mutableListOf<SubagentRequestStat>())
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = (1..2).map { child -> pool.submit(Callable {
                    val provider = SubagentRequestStats.provider(OpenAiChatCompletionsProvider, record("sub_$child"),
                        SubagentRequestBaseline()) { if (it.status != "running") records += it }
                    repeat(2) { provider.complete(providerRequest(url), AgentRunController()) {} }
                }) }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(4, records.size)
            records.groupBy { it.subagentId }.values.forEach { rows ->
                assertEquals(listOf(1, 2), rows.map { it.ordinal })
                assertEquals(listOf("first", "identical"), rows.map { it.historyChange })
                assertEquals(rows[0].id, rows[1].baselineId)
            }
        }
    }

    @Test
    fun diagnosticsStorageFailureCannotAbortModelResponse() {
        withServer { server, url ->
            server.createContext("/v1/chat/completions") { exchange ->
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(completeSse(12, 8).toByteArray()) }
            }
            val provider = SubagentRequestStats.provider(OpenAiChatCompletionsProvider, record(),
                SubagentRequestBaseline()) { error("fixture disk error") }
            assertEquals("ok", provider.complete(providerRequest(url), AgentRunController()) {}.assistantMessage.getString("content"))
        }
    }

    @Test
    fun disabledCollectionSkipsWritesAndReenabledCollectionStartsNewBaseline() {
        withServer { server, url ->
            server.createContext("/v1/chat/completions") { exchange ->
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(completeSse(12, 8).toByteArray()) }
            }
            var epoch: String? = null
            val records = linkedMapOf<String, SubagentRequestStat>()
            val client = SubagentRequestStats.provider(OpenAiChatCompletionsProvider, record(),
                SubagentRequestBaseline(), collectionEpoch = { epoch }) { records[it.id] = it }
            client.complete(providerRequest(url), AgentRunController()) {}
            assertTrue(records.isEmpty())
            epoch = "first-session"
            client.complete(providerRequest(url), AgentRunController()) {}
            client.complete(providerRequest(url), AgentRunController()) {}
            assertEquals(listOf("first", "identical"), records.values.map { it.historyChange })
            epoch = null
            client.complete(providerRequest(url), AgentRunController()) {}
            assertEquals(2, records.size)
            epoch = "second-session"
            client.complete(providerRequest(url), AgentRunController()) {}
            assertEquals("first", records.values.last().historyChange)
            assertNull(records.values.last().baselineId)
        }
    }

    @Test
    fun disablingDuringAttemptStillSavesItsTerminalUsage() {
        var epoch: String? = "enabled"
        withServer { server, url ->
            server.createContext("/v1/chat/completions") { exchange ->
                epoch = null
                exchange.requestBody.close()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(completeSse(12, 8).toByteArray()) }
            }
            val records = linkedMapOf<String, SubagentRequestStat>()
            val client = SubagentRequestStats.provider(OpenAiChatCompletionsProvider, record(),
                SubagentRequestBaseline(), collectionEpoch = { epoch }) { records[it.id] = it }
            client.complete(providerRequest(url), AgentRunController()) {}
            assertEquals(1, records.size)
            assertEquals("completed", records.values.single().status)
            assertEquals(8, records.values.single().cachedTokens)
            client.complete(providerRequest(url), AgentRunController()) {}
            assertEquals(1, records.size)
        }
    }

    private fun providerRequest(url: String) = ProviderRequest(
        AgentModelClient.ModelConfig(providerId = "fixture-relay", baseUrl = url, apiKey = "PRIVATE_CREDENTIAL",
            model = "gpt-fixture", systemPrompt = ""),
        json().getJSONArray("messages"), JSONArray(), sessionId = "fixture-session",
    )
    private fun completeSse(input: Int, cached: Int?): String {
        val usage = JSONObject().put("prompt_tokens", input).put("completion_tokens", 2)
        cached?.let { usage.put("prompt_tokens_details", JSONObject().put("cached_tokens", it)) }
        return "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("index", 0).put("delta", JSONObject().put("content", "ok")).put("finish_reason", "stop"))) +
            "\n\ndata: " + JSONObject().put("choices", JSONArray()).put("usage", usage) + "\n\ndata: [DONE]\n\n"
    }
    private fun withServer(test: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        try { test(server, "http://127.0.0.1:${server.address.port}/v1") }
        finally { server.stop(0); executor.shutdownNow() }
    }
}
