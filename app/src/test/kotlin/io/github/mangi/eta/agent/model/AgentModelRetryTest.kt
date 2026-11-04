package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    @Test
    fun truncatedSseTailRetriesThroughExistingBudget() {
        var calls = 0
        val events = mutableListOf<AgentEvent>()
        val result = complete(AgentModelRetry { _, _ -> }, provider { _, _ ->
            if (calls++ == 0) {
                readProviderSse("data: {\"choices\":[".byteInputStream(), AgentRunController()) { _, data ->
                    JSONObject(data)
                    true
                }
            }
            response()
        }, onEvent = events::add)
        assertEquals(2, calls)
        assertEquals("完成", result.response.assistantMessage.getString("content"))
        assertEquals("STREAM_INCOMPLETE", events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().reasonCode)
    }

    @Test
    fun repeatedIncompleteStreamsStopAtExistingRetryLimit() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> }, provider { _, _ ->
                calls++
                throw AgentModelFailure.incompleteStream("模型接口 SSE 流未正常结束")
            })
        }
        assertEquals(4, calls)
        assertFalse(failure.retryable)
        assertEquals("STREAM_INCOMPLETE", failure.code)
    }

    @Test
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    fun actualOkHttpHttp2DisconnectsUseRequestScopedFallback() {
        for (failure in listOf(
            okhttp3.internal.http2.StreamResetException(okhttp3.internal.http2.ErrorCode.CANCEL),
            okhttp3.internal.http2.StreamResetException(okhttp3.internal.http2.ErrorCode.REFUSED_STREAM),
            okhttp3.internal.http2.ConnectionShutdownException(),
        )) {
            var calls = 0
            val events = mutableListOf<AgentEvent>()
            val attempts = mutableListOf<ProviderRequest>()
            complete(AgentModelRetry { _, _ -> }, provider { request, _ ->
                attempts += request
                if (calls++ == 0) throw failure
                response()
            }, onEvent = events::add)
            assertEquals(2, calls)
            assertEquals(listOf(false, true), attempts.map { it.forceHttp1 })
            assertSame(attempts.first().messages, attempts.last().messages)
            assertEquals(attempts.first().sessionId, attempts.last().sessionId)
            assertEquals("MODEL_HTTP2_DISCONNECTED", events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().reasonCode)
        }
        // 协议配置和证书问题不因“HTTP/2 断流”一起放宽。
        assertNull(AgentModelFailure.transport(java.net.ProtocolException("unexpected protocol")))
    }

    @Test
    fun truncatedResponseIsRetryableButInvalidProtocolAndCertificatesAreNot() {
        assertEquals("MODEL_STREAM_TRUNCATED", AgentModelFailure.transport(java.net.ProtocolException("unexpected end of stream"))!!.code)
        assertNull(AgentModelFailure.transport(java.net.ProtocolException("invalid status line")))
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
    }

    @Test
    fun downgradeNeverChangesDefaultClientsOrTimeouts() {
        assertTrue(AgentHttpClient.modelClient.protocols.contains(okhttp3.Protocol.HTTP_2))
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT, true).protocols)
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION, true).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT, true).callTimeoutMillis)
        assertTrue(AgentHttpClient.modelClient.protocols.contains(okhttp3.Protocol.HTTP_2))
    }

    @Test
    fun http2FailureAfterHostedToolIsNotReplayedOrDowngraded() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> fail("hosted tool must not replay") }, provider { _, emit ->
                calls++
                emit(ProviderEvent.HostedToolStarted("search", "web_search"))
                throw IOException("stream was reset: CANCEL")
            })
        }
        assertEquals(1, calls)
        assertFalse(failure.retryable)
        assertEquals("MODEL_HTTP2_DISCONNECTED", failure.code)
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = onEvent,
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
