package io.github.mangi.eta.local.codexusage

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.testing.CodexUsageFixture
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CodexResetRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun fourOfficialCodesClearPendingAndSuccessReadsRealUsage() = withFixture { f ->
        val refreshed = CodexUsage(listOf(UsageWindow("codex", 37.0, 18000, 2000000000)), 10, 1)
        var refreshes = 0
        CodexResetRepository.usageRefresher = { _, provider, account, _ ->
            assertEquals(f.providerId, provider); assertEquals(f.first, account)
            refreshes++; refreshed
        }
        for (code in listOf("reset", "already_redeemed", "nothing_to_reset", "no_credit")) {
            val requests = mutableListOf<Request>()
            CodexResetRepository.httpClientProvider = { client { request ->
                requests += request
                val json = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals(setOf("redeem_request_id"), json.keys().asSequence().toSet())
                UUID.fromString(json.getString("redeem_request_id"))
                assertEquals(json.getString("redeem_request_id"), CodexResetRepository.pendingRedeemId(context, f.providerId, f.first))
                response(request, """{"code":"$code","windows_reset":["primary"]}""")
            } }
            val result = CodexResetRepository.consumeBlocking(context, f.providerId, f.first)
            if (code == "reset" || code == "already_redeemed") {
                assertTrue(result is CodexResetResult.Success)
                result as CodexResetResult.Success
                assertEquals(refreshed, result.usage)
                assertEquals(37.0, result.usage.windows.single().remaining!!, 0.0)
                assertEquals(listOf("primary"), result.resetWindowNames)
            } else assertTrue(result is CodexResetResult.Rejected)
            assertEquals(1, requests.size)
            assertEquals("POST", requests.single().method)
            assertEquals("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume", requests.single().url.toString())
            assertNull(CodexResetRepository.pendingRedeemId(context, f.providerId, f.first))
        }
        assertEquals(2, refreshes)
    }

    @Test fun successfulResetWithRefreshFailureIsNotReportedAsUnconfirmed() = withFixture { f ->
        CodexResetRepository.httpClientProvider = { client { response(it, """{"code":"reset","windows_reset":[]}""") } }
        CodexResetRepository.usageRefresher = { _, _, _, _ -> throw IOException("fixture refresh") }
        assertTrue(CodexResetRepository.consumeBlocking(context, f.providerId, f.first) is CodexResetResult.SuccessPendingRefresh)
        assertNull(CodexResetRepository.pendingRedeemId(context, f.providerId, f.first))
    }

    @Test fun timeoutRetainsSameUuidAcrossManualRetryAndRepositoryRead() = withFixture { f ->
        val ids = mutableListOf<String>()
        var fail = true
        CodexResetRepository.httpClientProvider = { client { request ->
            ids += JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("redeem_request_id")
            if (fail) throw java.net.SocketTimeoutException("fixture")
            response(request, """{"code":"already_redeemed","windows_reset":[]}""")
        } }
        val first = CodexResetRepository.consumeBlocking(context, f.providerId, f.first) as CodexResetResult.Failed
        assertTrue(first.unconfirmed)
        assertEquals(ids.single(), CodexResetRepository.pendingRedeemId(context, f.providerId, f.first))
        fail = false
        CodexResetRepository.consumeBlocking(context, f.providerId, f.first)
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertNull(CodexResetRepository.pendingRedeemId(context, f.providerId, f.first))
    }

    @Test fun concurrentClicksShareOneRequestAndRetainOriginalAccountBinding() = withFixture { f -> runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val joinerStarted = CountDownLatch(1)
        val attempts = AtomicInteger()
        var workspace: String? = null
        CodexResetRepository.httpClientProvider = { client { request ->
            attempts.incrementAndGet()
            workspace = request.header("ChatGPT-Account-Id")
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            response(request, """{"code":"nothing_to_reset","windows_reset":[]}""")
        } }
        val one = async(Dispatchers.IO) { CodexResetRepository.consume(context, f.providerId, f.first) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        f.accounts.select(f.providerId, ProviderAuthModes.OPENAI_CHATGPT, f.second)
        val two = async(Dispatchers.IO) {
            joinerStarted.countDown()
            CodexResetRepository.consume(context, f.providerId, f.first)
        }
        assertTrue(joinerStarted.await(10, TimeUnit.SECONDS))
        // Keep the request open until the second caller has entered the shared operation.
        kotlinx.coroutines.delay(50)
        release.countDown()
        assertEquals(one.await(), two.await())
        assertEquals(1, attempts.get())
        assertEquals("workspace-first", workspace)
        assertFalse(CodexResetRepository.isInFlight(f.providerId, f.first))
    } }

    @Test fun malformedPendingRecordBlocksNewRequestsAndPreservesEvidence() = withFixture { f ->
        val preferences = context.getSharedPreferences("codex_reset_pending_v1", Context.MODE_PRIVATE)
        preferences.edit().putString(CodexResetRepository.key(f.providerId, f.first), "{broken").commit()
        val count = AtomicInteger()
        CodexResetRepository.httpClientProvider = { client { count.incrementAndGet(); response(it, "{}") } }
        val result = CodexResetRepository.consumeBlocking(context, f.providerId, f.first) as CodexResetResult.Failed
        assertTrue(result.unconfirmed)
        assertEquals(0, count.get())
        assertEquals("{broken", preferences.getString(CodexResetRepository.key(f.providerId, f.first), null))
    }

    @Test fun unknownMalformedAndHttpFailuresRemainPendingWithoutAutomaticReplay() = withFixture { f ->
        for ((index, body) in listOf("{}", """{"outcome":"reset"}""", """{"code":"RESET"}""",
            """{"code":"reset","windows_reset":5}""", "not-json").withIndex()) {
            val count = AtomicInteger()
            CodexResetRepository.httpClientProvider = { client { count.incrementAndGet(); response(it, body) } }
            val account = if (index % 2 == 0) f.first else f.second
            val result = CodexResetRepository.consumeBlocking(context, f.providerId, account) as CodexResetResult.Failed
            assertTrue(result.unconfirmed)
            assertNotNull(CodexResetRepository.pendingRedeemId(context, f.providerId, account))
            assertEquals(1, count.get())
        }
    }

    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
    private fun response(request: Request, body: String) = Response.Builder().request(request).code(200)
        .message("OK").protocol(Protocol.HTTP_1_1).body(body.toResponseBody("application/json".toMediaType())).build()
    private fun withFixture(block: (CodexUsageFixture) -> Unit) {
        val oldClient = CodexResetRepository.httpClientProvider
        val oldRefresh = CodexResetRepository.usageRefresher
        CodexResetRepository.usageRefresher = { _, _, _, _ -> CodexUsage(emptyList(), 1, 1) }
        try { CodexUsageFixture(context, "reset-test-${System.nanoTime()}").use(block) }
        finally { CodexResetRepository.httpClientProvider = oldClient; CodexResetRepository.usageRefresher = oldRefresh }
    }
}
