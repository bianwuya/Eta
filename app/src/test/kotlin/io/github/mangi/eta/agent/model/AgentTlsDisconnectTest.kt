package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.EOFException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTlsDisconnectTest {
    @Test
    fun exactTlsConnectionClosureAndEofAreTransient() {
        for (failure in listOf(
            SSLException("connection closed"),
            SSLException("Connection closed by peer"),
            SSLException("Read failed", EOFException("peer closed")),
        )) {
            val classified = AgentModelFailure.transport(failure)!!
            assertEquals("MODEL_CONNECTION_FAILED", classified.code)
            assertTrue(classified.retryable)
        }
    }

    @Test
    fun certificatesHandshakesAndOtherTlsFailuresStillDoNotRetry() {
        for (failure in listOf(
            SSLHandshakeException("connection closed"),
            SSLPeerUnverifiedException("connection closed"),
            SSLException("certificate validation failed"),
            SSLException("connection closed", CertificateException("untrusted issuer")),
            SSLException("unsupported protocol"),
            SSLException("connection closed because invalid protocol"),
        )) assertNull(AgentModelFailure.transport(failure))
    }

    @Test
    fun tlsClosureUsesExistingBudgetWithoutReplayingTools() {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentModelRetry { _, delay -> delays += delay }.complete(
                initialRound = 1,
                request = ProviderRequest(
                    AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                    JSONArray(), JSONArray(),
                ),
                provider = object : AgentProviderClient {
                    override val id = "tls-fixture"
                    override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                        attempts++
                        throw SSLException("connection closed")
                    }
                },
                controller = AgentRunController(), onEvent = {}, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {},
            )
        }
        assertEquals(4, attempts)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertFalse(failure.retryable)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
    }
}
