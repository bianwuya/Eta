package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExecutionConversationNotificationTest {
    @Before fun reset() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun savedConversationTitleAndRenameAppearWithoutModelEventsAndKeepLockScreenPrivate() {
        RuntimeMessengerFixture().use { fixture ->
            val dao = EtaDatabase.get(fixture.context).conversationDao()
            fixture.result(fixture.worker { kotlinx.coroutines.runBlocking {
                dao.insertConversations(listOf(ConversationEntity("named-chat", "整理旅行计划", true,
                    createdAt = 1, updatedAt = 1)))
            } })
            val run = AgentRuntimeWire.ActiveRunSnapshot("named-run", "named-chat", title = "raw prompt must stay private")
            val lease = "run:${run.runId}"
            assertTrue(AgentExecutionService.acquire(fixture.context, lease) {})
            AgentExecutionService.updateRunStatus(fixture.context, AgentOverlayStatus.Reasoning, 1, listOf(run))
            shadowOf(Looper.getMainLooper()).idle()
            val host = Robolectric.buildService(AgentExecutionService::class.java).create()
            fun current(): Notification = fixture.context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single().notification
            try {
                fixture.await { current().extras.getString(Notification.EXTRA_TITLE) == "整理旅行计划" }
                assertEquals("整理旅·${fixture.context.getString(R.string.overlay_reasoning)}", current().extras.getString("android.shortCriticalText"))
                assertTrue(current().extras.getBoolean("android.requestPromotedOngoing"))
                assertEquals(Notification.VISIBILITY_PRIVATE, current().visibility)
                assertFalse(current().publicVersion.extras.toString().contains("整理旅行计划"))
                fixture.result(fixture.worker { kotlinx.coroutines.runBlocking {
                    dao.updateConversationTitle("named-chat", "周末旅行安排", 2)
                } })
                fixture.await { current().extras.getString(Notification.EXTRA_TITLE) == "周末旅行安排" }
                assertTrue(current().extras.getString("android.shortCriticalText").orEmpty().startsWith("周末旅·"))
                assertFalse(current().extras.toString().contains("raw prompt must stay private"))
            } finally {
                AgentExecutionService.release(lease)
                AgentExecutionService.updateRunStatus(fixture.context, null, 0)
                shadowOf(Looper.getMainLooper()).idle()
                host.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun reasoningAndAnsweringTransitionsUpdateLiveChipWhileRunPhaseStaysRunning() {
        val context = RuntimeEnvironment.getApplication()
        val run = AgentRuntimeWire.ActiveRunSnapshot("thinking-run", "thinking-chat")
        val lease = "run:${run.runId}"
        assertTrue(AgentExecutionService.acquire(context, lease) {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run))
        shadowOf(Looper.getMainLooper()).idle()
        val host = Robolectric.buildService(AgentExecutionService::class.java).create()
        fun current(): Notification = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        try {
            assertEquals(context.getString(R.string.overlay_reasoning), current().extras.getString("android.shortCriticalText"))
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.GeneratingAnswer, 1, listOf(run))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals(context.getString(R.string.execution_chip_answering), current().extras.getString("android.shortCriticalText"))
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals(context.getString(R.string.overlay_reasoning), current().extras.getString("android.shortCriticalText"))
        } finally {
            AgentExecutionService.release(lease)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            host.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun powerTitleIsReadableBeforeOpeningAppAndSwitchesWithNotificationTarget() {
        val context = RuntimeEnvironment.getApplication()
        val run = AgentRuntimeWire.ActiveRunSnapshot("power-title", "new-power-chat", title = "整理旅行计划",
            handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, createdAt = 1, interactionAt = 1)
        val next = run.copy(runId = "power-title-next", conversationId = "other-power-chat", title = "检查下载进度",
            createdAt = 2, interactionAt = 2)
        val leases = listOf("run:${run.runId}", "run:${next.runId}")
        leases.forEach { assertTrue(AgentExecutionService.acquire(context, it) {}) }
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run))
        shadowOf(Looper.getMainLooper()).idle()
        val host = Robolectric.buildService(AgentExecutionService::class.java).create()
        fun current(): Notification = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        try {
            assertEquals(run.title, current().extras.getString(Notification.EXTRA_TITLE))
            assertTrue(current().extras.getString("android.shortCriticalText").orEmpty().startsWith("整理旅·"))
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 2, listOf(run, next))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(next.title, current().extras.getString(Notification.EXTRA_TITLE))
            assertTrue(current().extras.getString("android.shortCriticalText").orEmpty().startsWith("检查下·"))
            val open = shadowOf(current().contentIntent).savedIntent
            assertEquals(next.runId, open.getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
            assertFalse(current().publicVersion.extras.toString().contains(next.title))
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run), suppressLiveUpdate = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(run.title, current().extras.getString(Notification.EXTRA_TITLE))
            assertFalse(current().extras.getBoolean("android.requestPromotedOngoing"))
        } finally {
            leases.forEach(AgentExecutionService::release)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            host.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
