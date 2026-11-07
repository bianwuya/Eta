package io.github.mangi.eta.local.codexusage

import android.app.Application
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CodexUsageCacheIsolationTest {
    @Test fun cachedWindowsAndTimesStayWithTheirProviderAndAccount() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("codex_usage_cache_v1", Context.MODE_PRIVATE)
        fun key(provider: String, account: String) = Uri.encode(provider) + ":" + Uri.encode(account)
        fun snapshot(remaining: Double, resetAt: Long, updatedAt: Long) = JSONObject()
            .put("updated_at", updatedAt)
            .put("windows", JSONArray().put(JSONObject().put("name", "codex")
                .put("remaining", remaining).put("seconds", 18_000L).put("reset_at", resetAt)))
            .toString()

        preferences.edit().clear()
            .putString(key("provider:a", "account"), snapshot(75.0, 10_000L, 1_000L))
            .putString(key("provider", "a:account"), snapshot(25.0, 20_000L, 2_000L))
            .putString(key("provider:a", "other"), snapshot(50.0, 30_000L, 3_000L))
            .commit()

        val first = CodexUsageRepository.cached(context, "provider:a", "account")!!
        val second = CodexUsageRepository.cached(context, "provider", "a:account")!!
        val third = CodexUsageRepository.cached(context, "provider:a", "other")!!
        assertEquals(75.0, first.windows.single().remaining!!, 0.0)
        assertEquals(10_000L, first.windows.single().resetAt)
        assertEquals(1_000L, first.updatedAt)
        assertEquals(25.0, second.windows.single().remaining!!, 0.0)
        assertEquals(20_000L, second.windows.single().resetAt)
        assertEquals(2_000L, second.updatedAt)
        assertEquals(50.0, third.windows.single().remaining!!, 0.0)
        assertEquals(30_000L, third.windows.single().resetAt)
        assertEquals(3_000L, third.updatedAt)
        assertNull(CodexUsageRepository.cached(context, "provider", "account"))
    }
}
