package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.EOFException
import java.net.ProtocolException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            handshake("connection closed"),
            handshake("Connection closed by peer"),
            handshake("Handshake failed", EOFException("connection closed")),
            SSLException("transport failed", handshake("Handshake failed", EOFException())),
            handshake("Handshake failed", SSLException("connection closed")),
        )) {
            val classified = AgentModelFailure.transport(failure)!!
            assertEquals("MODEL_CONNECTION_FAILED", classified.code)
            assertTrue(classified.retryable)
        }
    }

    @Test
    fun unclassifiedHandshakesAndOtherTlsFailuresStillDoNotRetry() {
        for (failure in listOf(
            handshake("Handshake failed"),
            handshake("Remote host terminated the handshake"),
            handshake("certificate"),
            SSLPeerUnverifiedException("connection closed"),
            SSLException("certificate validation failed"),
            SSLException("connection closed", CertificateException("untrusted issuer")),
            SSLException("unsupported protocol"),
            SSLException("connection closed because invalid protocol"),
        )) assertNull(AgentModelFailure.transport(failure))
    }

    @Test
    fun permanentCauseTypesWinOverEofAndClosureAtAnyDepth() {
        for (permanent in listOf(
            CertificateException("untrusted issuer"),
            CertPathValidatorException("invalid path"),
            CertPathBuilderException("invalid path"),
            SSLPeerUnverifiedException("identity mismatch"),
            SSLProtocolException("invalid TLS record"),
            ProtocolException("invalid negotiation"),
        )) {
            permanent.initCause(EOFException("connection closed"))
            assertNull(AgentModelFailure.transport(handshake("connection closed", permanent)))
            assertNull(AgentModelFailure.transport(SSLException("connection closed", EOFException().apply { initCause(permanent) })))
        }
    }

    @Test
    fun explicitIdentitySniAndProtocolAlertsWinOverWrappedEof() {
        for (message in listOf(
            "CERTIFICATE_VERIFY_FAILED", "Hostname example.invalid not verified", "Trust anchor not found",
            "TLSV1_ALERT_UNRECOGNIZED_NAME", "Received fatal alert: unrecognized_name",
            "TLSV1_ALERT_PROTOCOL_VERSION", "unsupported protocol", "wrong version number",
            "Received fatal alert: handshake_failure", "no shared cipher",
        )) {
            assertNull(message, AgentModelFailure.transport(handshake(message, EOFException("connection closed"))))
            assertNull(message, AgentModelFailure.transport(handshake("connection closed", EOFException(message))))
        }
    }

    @Test
    fun cyclicCausesTerminateWithoutTreatingUnknownHandshakeAsClosure() {
        val first = handshake("Handshake failed")
        val second = SSLException("unknown TLS failure", first)
        first.initCause(second)
        assertNull(AgentModelFailure.transport(first))
    }

    @Test
    fun handshakeEofUsesExistingBudgetWithoutChangingRequest() {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val events = mutableListOf<AgentEvent>()
        val originalRequest = request()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentModelRetry { _, delay -> delays += delay }.complete(
                initialRound = 1,
                request = originalRequest,
                provider = object : AgentProviderClient {
                    override val id = "tls-fixture"
                    override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                        attempts++
                        assertSame(originalRequest, request)
                        throw handshake("Handshake failed", EOFException("connection closed"))
                    }
                },
                controller = AgentRunController(), onEvent = events::add, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {},
            )
        }
        assertEquals(4, attempts)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.ModelRetryScheduled>().map { it.attempt })
        assertFalse(failure.retryable)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
    }

    @Test
    fun handshakeEofCanRecoverButExplicitSniAlertStopsAfterOneAttempt() {
        for (sniAlert in listOf(false, true)) {
            val originalRequest = request()
            val expected = ProviderResponse(JSONObject().put("content", "done"))
            val firstFailure = handshake(
                if (sniAlert) "TLSV1_ALERT_UNRECOGNIZED_NAME" else "Handshake failed",
                EOFException("connection closed"),
            )
            var attempts = 0
            val delays = mutableListOf<Long>()
            val provider = object : AgentProviderClient {
                override val id = "handshake-fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    assertSame(originalRequest, request)
                    if (attempts++ == 0) throw firstFailure
                    return expected
                }
            }
            val invoke = {
                AgentModelRetry { _, delay -> delays += delay }.complete(
                    initialRound = 1, request = originalRequest, provider = provider, controller = AgentRunController(),
                    onEvent = {}, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {},
                )
            }
            if (sniAlert) {
                assertSame(firstFailure, assertThrows(SSLHandshakeException::class.java) { invoke() })
                assertEquals(1, attempts)
                assertTrue(delays.isEmpty())
            } else {
                val result = invoke()
                assertSame(expected, result.response)
                assertEquals(2, result.round)
                assertEquals(2, attempts)
                assertEquals(listOf(2_000L), delays)
            }
        }
    }

    private fun handshake(message: String, cause: Throwable? = null) = SSLHandshakeException(message).apply {
        if (cause != null) initCause(cause)
    }

    private fun request() = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "prefix"),
        JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")), JSONArray(),
    )
}
