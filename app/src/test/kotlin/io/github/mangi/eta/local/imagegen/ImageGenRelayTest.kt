package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
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
        ProviderRepository.init(context)
        val provider = OpenAiCompatibleProviderSetting(
            id = "image-relay-test", name = "Relay fixture", baseUrl = "https://relay.example.invalid/v1/",
            apiKey = "fixture-api-key",
        )
        ProviderRepository.addProvider(provider)
        val requests = AtomicInteger()
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
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(400).message("Fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ImageGenClient(context, client).generate("fixture prompt", AgentRunController(),
            ImageGenConfig(source = ImageGenSource.OPENAI_API, providerId = provider.id, model = "relay-image-model"))
        assertEquals("IMAGE_GEN_REJECTED", result.getString("code"))
        assertEquals(1, requests.get())
    }
}
