package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val prompt = JSONObject().put("prompt", "Synthetic landscape")

class ImageGenToolTest {
    @Test
    fun catalogOmitsUnconfiguredAndDisabledImageTool() {
        val name = ImageGenToolCatalog.TOOL_NAME
        fun hasTool(configured: Boolean, disabled: Set<String> = emptySet()): Boolean {
            val tools = AgentToolCatalog.build(false, false,
                capabilities = AgentToolCapabilities(rootAvailable = false), disabledTools = disabled, imageGeneration = configured)
            return (0 until tools.length()).any { tools.getJSONObject(it).getJSONObject("function").getString("name") == name }
        }
        assertFalse(hasTool(false))
        assertTrue(hasTool(true))
        assertFalse(hasTool(true, setOf(name)))
    }

    @Test
    fun executionRechecksAuthorizationAndRejectsModelOverrides() {
        var selected: ImageGenConnection? = null
        var calls = 0
        val tool = ImageGenTool({ selected }) { _, _, connection ->
            calls++
            JSONObject().put("ok", true).put("model", connection.model)
        }
        assertEquals("IMAGE_GEN_NOT_CONFIGURED", tool.execute("a", prompt).code())
        selected = connection()
        val override = JSONObject(prompt.toString()).put("model", "chat-model")
        assertEquals("IMAGE_GEN_INVALID_ARGUMENT", tool.execute("b", override).code())
        val result = JSONObject(tool.execute("c", prompt).content)
        assertEquals("refresh", result.getString("model"))
        selected = null
        assertEquals("IMAGE_GEN_NOT_CONFIGURED", tool.execute("d", prompt).code())
        assertEquals(1, calls)
    }

    @Test
    fun unknownOutcomesAreNotReplayedAndClosedRunDoesNotStartGeneration() {
        var calls = 0
        val tool = ImageGenTool(::connection) { _, _, _ ->
            calls++
            error("fixture failure after dispatch")
        }
        val first = tool.execute("replay", prompt)
        assertEquals("IMAGE_GEN_OUTCOME_UNKNOWN", first.code())
        assertTrue(JSONObject(first.content).getBoolean("outcome_unknown"))
        assertEquals(first, tool.execute("replay", prompt))
        tool.close()
        assertThrows(AgentRunCancelledException::class.java) { tool.execute("new-call", prompt) }
        assertEquals(1, calls)
    }

    @Test
    fun standardGenerationSendsOneRequestForTheOriginalModelId() {
        var requests = 0
        val client = imageGenFixtureHttp { request ->
            requests++
            assertEquals("POST", request.method)
            assertEquals("/v1/images/generations", request.url.encodedPath)
            assertEquals("Bearer fixture-images-key", request.header("Authorization"))
            val body = checkNotNull(request.body)
            assertTrue(body.isOneShot())
            val args = JSONObject(Buffer().also(body::writeTo).readUtf8())
            assertEquals("refresh", args.getString("model"))
            assertEquals(1, args.getInt("n"))
            assertEquals("Synthetic landscape", args.getString("prompt"))
            200 to """{"data":[{"b64_json":"c3ludGhldGlj"}]}"""
        }
        val generator = ImageGenClient({ b64, _ ->
            assertEquals("c3ludGhldGlj", b64)
            ImageGenOutput.Saved("content://fixture/images/1", "Download/Eta/Generated/fixture.png", "image/png", 1, 1)
        }, client)
        val result = generator.generate("Synthetic landscape", AgentRunController(), connection())
        assertTrue(result.getBoolean("ok"))
        assertEquals("refresh", result.getString("model"))
        assertEquals("content://fixture/images/1", result.getString("uri"))
        assertEquals(1, requests)
    }

    @Test
    fun noRetryRedirectOrRemoteImageUrlFallback() {
        val transport = imageGenFixtureHttp { 503 to "" }
        val restricted = transport.imageGenBuilder().build()
        assertFalse(restricted.followRedirects)
        assertFalse(restricted.followSslRedirects)
        assertFalse(restricted.retryOnConnectionFailure)
        val failure = ImageGenClient({ _, _ -> error("must not save") }, transport)
            .generate("Synthetic landscape", AgentRunController(), connection())
        assertTrue(failure.getBoolean("outcome_unknown"))
        assertFalse(failure.getBoolean("retryable"))

        val urlOnly = imageGenFixtureHttp { 200 to """{"data":[{"url":"https://other.example/1"}]}""" }
        val invalid = ImageGenClient({ _, _ -> error("must not save a URL") }, urlOnly)
            .generate("Synthetic landscape", AgentRunController(), connection())
        assertEquals("IMAGE_GEN_RESULT_FAILED", invalid.getString("code"))
    }

    private fun connection() = ImageGenConnection(checkNotNull(ImageGenEndpoint.resolve("https://images.example")), "refresh", "fixture-images-key")

    private fun io.github.mangi.eta.agent.model.AgentModelClient.ToolResult.code() = JSONObject(content).getString("code")
}

/** Consumes requests in memory. A test can never resolve DNS or connect a socket. */
internal fun imageGenFixtureHttp(answer: (Request) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder()
    .dns { error("Network is forbidden in image tests") }
    .addInterceptor { call ->
        val request = call.request()
        val (code, body) = answer(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).build()
    }.build()
