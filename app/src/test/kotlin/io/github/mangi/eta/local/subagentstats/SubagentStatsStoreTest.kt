package io.github.mangi.eta.local.subagentstats

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubagentStatsStoreTest {
    @Test
    fun updatesOneAttemptAndRoundTripsConversations() {
        val store = SubagentStatsStore.get(RuntimeEnvironment.getApplication())
        val first = SubagentRequestStat(id = "persistent-1", conversationId = "conv-1", subagentId = "sub_1",
            invocationId = "call-1", parentRunId = "run-1", profile = "coder", providerId = "relay",
            model = "gpt-fixture", purpose = "CHAT", ordinal = 1)
        store.save(first)
        store.save(first.copy(status = "completed", inputTokens = 100, cachedTokens = 80))
        store.save(first.copy(id = "persistent-2", conversationId = "conv-2"))
        val rows = store.records()
        assertEquals(2, rows.size)
        assertEquals(80, rows.single { it.id == first.id }.cachedTokens)
        assertEquals(setOf("conv-1", "conv-2"), rows.map { it.conversationId }.toSet())
    }
}
