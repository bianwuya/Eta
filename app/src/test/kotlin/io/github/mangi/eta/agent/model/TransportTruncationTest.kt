package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransportTruncationTest {
    @Test(timeout = 20_000) fun socketClosedBeforeContentLengthRetriesSameBodyWithoutCommittingPartialReply() {
        runServer(truncateFirst = true) { result, count, bodies, events ->
            assertEquals(2, count)
            assertEquals(1, bodies.toSet().size)
            assertEquals("完整回答", result.response.assistantMessage.getString("content"))
            assertEquals("MODEL_STREAM_TRUNCATED", events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().reasonCode)
        }
    }
    @Test(timeout = 20_000) fun completedFinishReasonWithoutDoneMarkerDoesNotReplay() {
        runServer(truncateFirst = false) { result, count, _, events ->
            assertEquals(1, count)
            assertEquals("完整回答", result.response.assistantMessage.getString("content"))
            assertTrue(events.none { it is AgentEvent.ModelRetryScheduled })
        }
    }
    private fun runServer(truncateFirst: Boolean, assertResult: (AgentModelRetry.Result, Int, List<String>, List<AgentEvent>) -> Unit) {
        val count = AtomicInteger()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte < 0) error("incomplete request")
                        header.append(byte.toChar())
                    }
                    val length = Regex("content-length: ([0-9]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val n = input.read(body, offset, length - offset)
                        if (n < 0) error("incomplete body")
                        offset += n
                    }
                    bodies += body.toString(Charsets.UTF_8)
                    val partial = count.getAndIncrement() == 0 && truncateFirst
                    val data = if (partial) "data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n" else
                        "data: {\"choices\":[{\"delta\":{\"content\":\"完整回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                    val bytes = data.toByteArray()
                    val claimed = bytes.size + if (partial) 128 else 0
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: $claimed\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                    // Closing the actual TCP socket guarantees EOF, unlike HttpServer's pooled exchange.
                }
            } catch (_: java.net.SocketException) { if (!server.isClosed) throw IllegalStateException("fixture socket failed") }
        }
        try {
            val events = mutableListOf<AgentEvent>()
            val req = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "http://127.0.0.1:${server.localPort}", apiKey = "fixture", model = "fixture", systemPrompt = ""),
                JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")), JSONArray())
            val result = AgentModelRetry { _, _ -> }.complete(1, req, OpenAiChatCompletionsProvider, AgentRunController(), events::add, { _, _ -> }, {})
            assertResult(result, count.get(), bodies, events)
        } finally { server.close(); executor.shutdownNow() }
    }
}
