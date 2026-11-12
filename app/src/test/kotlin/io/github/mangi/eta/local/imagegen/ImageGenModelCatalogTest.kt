package io.github.mangi.eta.local.imagegen

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageGenModelCatalogTest {
    @Test fun independentConnectionFetchesImageChoicesUsingOnlyOneReadRequest() = runBlocking {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            paths += request.requestURI.path
            assertEquals("GET", request.requestMethod)
            assertEquals("Bearer fixture-image-key", request.requestHeaders.getFirst("Authorization"))
            val body = """{"data":[{"id":"gpt-image-fixture"},{"id":"gpt-chat-fixture"},{"id":"custom-painter","output_modalities":["image"]},{"id":"gpt-image-fixture"}]}""".toByteArray()
            request.sendResponseHeaders(200, body.size.toLong()); request.responseBody.use { it.write(body) }; request.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            for (suffix in listOf("", "/v1", "/v1/images/generations")) {
                val models = ImageGenModelCatalog.fetch(base + suffix, "fixture-image-key").getOrThrow()
                assertEquals(listOf("gpt-image-fixture", "custom-painter"), models.map { it.modelId })
            }
            assertEquals(List(3) { "/v1/models" }, paths)
        } finally { server.stop(0) }
    }

    @Test fun failedCatalogDoesNotLeakResponseOrInventChoicesAndNeverFollowsRedirects() = runBlocking {
        var calls = 0
        var status = 401
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            calls++
            val bytes = "private-fixture-response".toByteArray()
            request.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/other")
            request.sendResponseHeaders(status, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.start()
        try {
            for (code in listOf(401, 403, 404, 429, 500, 302)) {
                status = code
                val before = calls
                val result = ImageGenModelCatalog.fetch("http://127.0.0.1:${server.address.port}", "fixture")
                assertTrue(result.isFailure); assertEquals(before + 1, calls)
                assertFalse(result.exceptionOrNull().toString().contains("private-fixture"))
            }
        } finally { server.stop(0) }
    }
}
