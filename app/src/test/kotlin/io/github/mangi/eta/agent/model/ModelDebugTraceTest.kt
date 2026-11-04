package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.File
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ModelDebugTraceTest {
    @Test
    fun normalSseStagesAndFailureTypesAreCapturedWithoutPrivateContents() {
        val context = RuntimeEnvironment.getApplication()
        val log = File(context.filesDir, "model-debug-trace.jsonl")
        log.delete()
        ModelDebugTrace.initialize(context)
        val body = """{"model":"fixture","messages":[{"role":"system","content":"PRIVATE_PROMPT_123"}],"tools":[],"prompt_cache_key":"PRIVATE_CACHE_456","reasoning_effort":"max"}"""
        val request = Request.Builder().url("https://example.invalid/v1/chat/completions")
            .header("Authorization", "Bearer PRIVATE_API_KEY_789")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val trace = ModelDebugTrace.begin(request)
        assertNotNull(trace)
        ModelDebugTrace.consume(trace) {
            readProviderSse("data: {\"choices\":[]}\n\ndata: [DONE]\n\n".byteInputStream(), AgentRunController()) { _, data -> data != "[DONE]" }
        }
        assertThrows(SSLException::class.java) {
            ModelDebugTrace.consume(trace) { throw SSLException("PRIVATE_EXCEPTION_BODY_999") }
        }
        val raw = log.readText()
        for (secret in listOf("PRIVATE_PROMPT_123", "PRIVATE_CACHE_456", "PRIVATE_API_KEY_789", "PRIVATE_EXCEPTION_BODY_999")) {
            assertFalse(raw.contains(secret))
        }
        val events = raw.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        assertEquals(listOf("request_metadata", "sse_first_data", "sse_done_marker", "sse_handler_terminal", "provider_complete", "provider_failed"), events.map { it.getString("event") })
        assertTrue(events.first().getString("system_hash").length == 20)
        assertEquals("javax.net.ssl.SSLException", events.last().getJSONArray("exceptions").getString(0))
        val later = ModelDebugTrace.begin(request)!!
        later.stage("independent_request")
        val last = log.readLines().map(::JSONObject)
        assertEquals(events.first().getString("body_hash"), last[last.size - 2].getString("body_hash"))
        assertFalse(events.first().getString("request") == last.last().getString("request"))
    }
}
