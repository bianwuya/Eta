package io.github.mangi.eta.local.codexusage

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.testing.CodexUsageFixture
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CpaResetCreditsTest {
    @Test fun separateCreditsEndpointUsesPinnedAccountAndPersistsCountAndExpiry() {
        val context = RuntimeEnvironment.getApplication()
        val old = CodexUsageRepository.httpClientProvider
        CodexUsageFixture(context, "cpa-credits").use { fixture ->
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                if (requests.size == 1) {
                    fixture.accounts.select(fixture.providerId, io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT, fixture.second)
                }
                val body = when (request.url.encodedPath) {
                    "/backend-api/wham/usage" -> """{"rate_limit":{"primary_window":{"used_percent":40}},"rate_limit_reset_credits":{"available_count":1}}"""
                    "/backend-api/wham/rate-limit-reset-credits" -> """{"available_count":3,"applicable_available_count":2,"credits":[{"id":"a","reset_type":"codex_rate_limits","status":"available","expires_at":"2099-01-01T00:00:00Z"},{"id":"b","reset_type":"other","status":"available","expires_at":"2099-01-01T00:00:00Z"}]}"""
                    else -> error("Unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            CodexUsageRepository.httpClientProvider = { client }
            try {
                val result = CodexUsageRepository.refresh(context, fixture.providerId, fixture.first, AgentRunController())
                assertEquals("CPA credits endpoint must be read after usage", 2, requests.size)
                assertEquals(requests[0].header("Authorization"), requests[1].header("Authorization"))
                assertEquals(requests[0].header("ChatGPT-Account-Id"), requests[1].header("ChatGPT-Account-Id"))
                assertEquals(3, result.resetCreditsAvailable)
                val cached = requireNotNull(CodexUsageRepository.cached(context, fixture.providerId, fixture.first))
                assertEquals(3, cached.resetCreditsAvailable)
                val raw = context.getSharedPreferences("codex_usage_cache_v1", 0).all.values.joinToString()
                assertTrue("expiry must survive reopening", raw.contains("2099-01-01T00:00:00Z"))
                assertTrue("applicable count must be retained", raw.contains("reset_credits_applicable"))
            } finally { CodexUsageRepository.httpClientProvider = old }
        }
    }

    @Test fun creditsFailurePreservesSuccessfulUsageWithoutConsumingAnything() {
        val context = RuntimeEnvironment.getApplication()
        val old = CodexUsageRepository.httpClientProvider
        CodexUsageFixture(context, "cpa-optional-failure").use { fixture ->
            val methods = mutableListOf<String>()
            CodexUsageRepository.httpClientProvider = {
                OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (!request.url.encodedPath.endsWith("/usage")) throw IOException("fixture optional endpoint failure")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"rate_limit":{"primary_window":{"used_percent":25}},"rate_limit_reset_credits":{"available_count":1}}"""
                            .toResponseBody("application/json".toMediaType())).build()
                }.build()
            }
            try {
                val result = CodexUsageRepository.refresh(context, fixture.providerId, fixture.first, AgentRunController())
                assertEquals(75.0, result.windows.single().remaining!!, 0.0)
                assertEquals(1, result.resetCreditsAvailable)
                assertEquals(listOf("GET", "GET"), methods)
            } finally { CodexUsageRepository.httpClientProvider = old }
        }
    }
}
