package io.github.mangi.eta.ui.app

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWebReadinessTest {
    @Test fun aLocalHttpResponseConfirmsReadinessWithoutSendingAnyToken() {
        val (ready, request) = probe("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        assertTrue(ready)
        assertEquals("GET / HTTP/1.1", request)
    }

    @Test fun aListeningPortWithoutHttpIsNotReady() {
        assertFalse(probe("not an HTTP service\r\n").first)
    }

    @Test fun aRedirectIsNotReadyEvenIfItPointsBackToLoopback() {
        assertFalse(probe("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").first)
    }

    private fun probe(response: String): Pair<Boolean, String?> {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 2_000
            val request = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader()
                    val line = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) { /* Skip fixture headers. */ }
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    line
                }
            }
            Thread(request, "kimi-web-test-loopback").apply { isDaemon = true; start() }
            return kimiWebAcceptsLocalHttp(server.localPort) to request.get(2, TimeUnit.SECONDS)
        }
    }
}
