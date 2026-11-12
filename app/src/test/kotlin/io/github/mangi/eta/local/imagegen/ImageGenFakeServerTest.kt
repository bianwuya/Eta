package io.github.mangi.eta.local.imagegen

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenFakeServerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val credentials = ImageGenCredentials(object : ProviderCredentialStorage {
        override fun read(providerId: String) = ProviderCredential(authMode = "api_key", clientId = "fixture", accessToken = "fixture-only")
        override fun write(providerId: String, credential: ProviderCredential) = Unit
        override fun delete(providerId: String) = Unit
    })
    private fun png(): String {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        finally { image.recycle() }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    @Test fun realHttpUsesIndependentRootKeyAndModelForAllSupportedAddressForms() {
        val records = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", png()))).toString().toByteArray()
        server.createContext("/") { exchange ->
            records += exchange.requestURI.path
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-only")
            check(exchange.requestHeaders.getFirst("ChatGPT-Account-Id") == null)
            check(request.getString("model") == "fixture-image-model")
            check(request.getInt("n") == 1)
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }; exchange.close()
        }
        server.start()
        val output = File(context.cacheDir, "fake-images-${System.nanoTime()}")
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            for (suffix in listOf("", "/v1", "/v1/images/generations")) {
                val config = ImageGenConfig(source = ImageGenSource.OPENAI_API, profileId = "isolated", endpoint = root + suffix,
                    keyConfigured = true, model = "fixture-image-model")
                val result = ImageGenClient(context, credentials = credentials,
                    saveImage = { encoded, controller -> ImageGenOutput.save(encoded, controller, output) }).generate("fixture", AgentRunController(), config)
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertTrue(File(result.getString("path")).isFile)
                assertEquals(2, result.getInt("width"))
            }
            assertEquals(List(3) { "/v1/images/generations" }, records)
        } finally { server.stop(0); output.deleteRecursively() }
    }

    @Test fun httpErrorsRedirectsEmptyDecodeAndStorageFailuresNeverRetryOrExposeResponseSecrets() {
        var status = 401
        var response = """{"error":{"message":"fixture-private-message"}}"""
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            exchange.requestBody.readBytes()
            if (status == 302) exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/other")
            val data = response.toByteArray()
            exchange.sendResponseHeaders(status, data.size.toLong()); exchange.responseBody.use { it.write(data) }; exchange.close()
        }
        server.start()
        try {
            val config = ImageGenConfig(source = ImageGenSource.OPENAI_API, profileId = "isolated", endpoint = "http://127.0.0.1:${server.address.port}",
                keyConfigured = true, model = "image-fixture")
            for (code in listOf(401, 403, 404, 429, 500, 502, 503, 302)) {
                status = code
                val before = calls
                val result = ImageGenClient(context, credentials = credentials).generate("fixture", AgentRunController(), config)
                assertFalse(result.getBoolean("ok"))
                assertEquals(before + 1, calls)
                assertFalse(result.getBoolean("automatic_retry"))
                assertFalse(result.toString().contains("fixture-private-message"))
            }
            status = 200
            for (data in listOf("{}", "", """{"data":[{"b64_json":"bm90LWltYWdl"}]}""")) {
                response = data
                val result = ImageGenClient(context, credentials = credentials).generate("fixture", AgentRunController(), config)
                assertFalse(result.getBoolean("ok")); assertTrue(result.getBoolean("outcome_unknown"))
            }
            response = JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", png()))).toString()
            val failedSave = ImageGenClient(context, credentials = credentials, saveImage = { _, _ -> throw java.io.IOException("disk fixture") })
                .generate("fixture", AgentRunController(), config)
            assertFalse(failedSave.getBoolean("ok")); assertTrue(failedSave.getBoolean("outcome_unknown"))
            val beforeCancel = calls
            assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
                ImageGenClient(context, credentials = credentials).generate("fixture", AgentRunController().apply { cancel() }, config)
            }
            assertEquals(beforeCancel, calls)
        } finally { server.stop(0) }
    }
}
