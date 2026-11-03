package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayStreamFailureTest {
    @Test
    fun relayUpstreamFailuresAreTransientButOtherErrorsAreNot() {
        for (error in listOf(
            JSONObject().put("message", "upstream failed"),
            JSONObject().put("code", "unknown_error").put("type", "openai_error").put("message", "upstream failed"),
            JSONObject().put("code", "upstream_http2_stream_error").put("message", "Upstream HTTP/2 stream failed"),
            JSONObject().put("type", "upstream_error").put("message", "upstream failed"),
            JSONObject().put("code", "upstream_failed"),
            JSONObject().put("metadata", JSONObject().put("error_type", "upstream_http2_stream_error")),
        )) assertTrue(error.toString(), AgentModelFailure.stream(error, "fixture").retryable)
        for (error in listOf(
            JSONObject().put("message", "unknown error"),
            JSONObject().put("message", "upstream failed: bad API key"),
            JSONObject().put("type", "invalid_request_error").put("message", "upstream failed"),
            JSONObject().put("code", "insufficient_quota").put("message", "upstream failed"),
            JSONObject().put("code", "upstream_error").put("message", "quota exceeded"),
            JSONObject().put("type", "authentication_error").put("message", "upstream failed"),
        )) assertFalse(error.toString(), AgentModelFailure.stream(error, "fixture").retryable)
    }

    @Test
    fun untypedSseErrorRetriesSameModelRequestAndRetainsOnlySuccessfulReply() {
        val requests = mutableListOf<String>()
        val count = AtomicInteger()
        withServer({
            if (count.getAndIncrement() < 2) {
                "data: {\"error\":{\"type\":\"openai_error\",\"code\":\"unknown_error\",\"message\":\"upstream failed\"}}\n\n"
            } else {
                "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
        }, requests) { request ->
            val events = mutableListOf<AgentEvent>()
            val delays = mutableListOf<Long>()
            val result = AgentModelRetry { _, delay -> delays += delay }.complete(
                1, request, OpenAiChatCompletionsProvider, AgentRunController(), events::add, { _, _ -> }, {},
            )
            assertEquals("完成", result.response.assistantMessage.getString("content"))
            assertEquals(3, requests.size)
            assertEquals(1, requests.toSet().size)
            assertEquals(listOf(2_000L, 4_000L), delays)
            assertEquals(listOf(1, 2), events.filterIsInstance<AgentEvent.ModelRetryScheduled>().map { it.attempt })
        }
    }

    @Test
    fun persistentRelaySseErrorStopsAtExistingThreeRetryBudget() {
        val requests = mutableListOf<String>()
        withServer({ "data: {\"error\":{\"code\":\"upstream_http2_stream_error\",\"message\":\"Upstream HTTP/2 stream failed\"}}\n\n" }, requests) { request ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> }.complete(
                    1, request, OpenAiChatCompletionsProvider, AgentRunController(), {}, { _, _ -> }, {},
                )
            }
            assertEquals(4, requests.size)
            assertFalse(failure.retryable)
            assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        }
    }

    private fun withServer(body: () -> String, requests: MutableList<String>, block: (ProviderRequest) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests += exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = body().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(ProviderRequest(
                AgentModelClient.ModelConfig(baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "fixture", model = "gpt-fixture", systemPrompt = ""),
                JSONArray().put(JSONObject().put("role", "user").put("content", "继续")), JSONArray(),
                sessionId = "relay-retry-fixture",
            ))
        } finally { server.stop(0) }
    }
}
