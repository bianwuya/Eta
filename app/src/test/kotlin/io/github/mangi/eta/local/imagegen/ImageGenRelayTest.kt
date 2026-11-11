package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageGenRelayTest {
    @Test fun customRelayGetsFixedEndpointKeyAndModelWithoutSubscriptionAuthOrRetry() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val requests = AtomicInteger()
        var responseCode = 400
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val request = chain.request()
            assertEquals("https://relay.example.invalid/v1/images/generations", request.url.toString())
            assertEquals("Bearer fixture-api-key", request.header("Authorization"))
            assertNull(request.header("ChatGPT-Account-Id"))
            val buffer = okio.Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals("relay-image-model", json.getString("model"))
            assertEquals(1, json.getInt("n"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("Fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val keys = ImageGenCredentials(object : io.github.mangi.eta.agent.auth.ProviderCredentialStorage {
            override fun read(providerId: String) = io.github.mangi.eta.agent.auth.ProviderCredential(
                authMode = "api_key", clientId = "fixture", accessToken = "fixture-api-key")
            override fun write(providerId: String, credential: io.github.mangi.eta.agent.auth.ProviderCredential) = Unit
            override fun delete(providerId: String) = Unit
        })
        for ((status, code) in listOf(400 to "IMAGE_GEN_REJECTED", 401 to "IMAGE_GEN_AUTH_EXPIRED",
            429 to "IMAGE_GEN_REJECTED", 503 to "IMAGE_GEN_OUTCOME_UNKNOWN")) {
            responseCode = status
            requests.set(0)
            val result = ImageGenClient(context, client, keys).generate("fixture prompt", AgentRunController(),
                ImageGenConfig(source = ImageGenSource.OPENAI_API, profileId = "relay-profile", model = "relay-image-model",
                    endpoint = "https://relay.example.invalid/v1/images/generations", keyConfigured = true))
            assertEquals(code, result.getString("code"))
            assertEquals(1, requests.get())
            assertFalse(result.getBoolean("automatic_retry"))
        }
    }
}
