package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class)
class AgentExecutionServiceTest {
    @Test
    fun notificationHasTargetedSingleRunAndPublicPrivateVersion() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "run:notification-single"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        val run = AgentRuntimeWire.ActiveRunSnapshot(
            "notification-single", "conversation", title = "私人标题",
        )
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run))
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals("私人标题", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertNotNull(notification.publicVersion)
            assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("私人标题"))
            assertEquals(1, notification.actions.size)
            assertEquals(context.getString(R.string.task_stop), notification.actions.single().title.toString())
            val stopIntent = shadowOf(notification.actions.single().actionIntent).savedIntent
            assertEquals("notification-single", stopIntent.getStringExtra("run_id"))
            assertEquals("notification-single", stopIntent.data?.lastPathSegment)
            val openIntent = shadowOf(notification.contentIntent).savedIntent
            assertEquals("notification-single", openIntent.getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
        } finally {
            AgentExecutionService.release(leaseId)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun multipleRunsDoNotOfferAmbiguousGlobalStop() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "run:notification-multiple"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        val runs = listOf(
            AgentRuntimeWire.ActiveRunSnapshot("a", "a", title = "A"),
            AgentRuntimeWire.ActiveRunSnapshot("b", "b", title = "B"),
        )
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 2, runs)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(notification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("2"))
            assertTrue(notification.actions.isNullOrEmpty())
            assertEquals(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_ACTIVE_TASKS,
                shadowOf(notification.contentIntent).savedIntent.action)
        } finally {
            AgentExecutionService.release(leaseId)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [33, 36])
    fun agentRunPromotionEndsWhileTerminalSessionRemains() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:terminal-session"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, activeRunCount = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(Build.VERSION.SDK_INT >= 36, notification.extras.getBoolean("android.requestPromotedOngoing"))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(Notification.ProgressStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
                assertEquals(context.getString(R.string.overlay_reasoning), notification.extras.getString("android.shortCriticalText"))
            }

            AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            assertFalse(remaining.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(remaining.extras.containsKey("android.shortCriticalText"))
            assertTrue(remaining.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun foregroundExecutionUsesOnlyServiceTypesSupportedByThePlatform() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                service.foregroundServiceType,
            )
        } finally {
            controller.destroy()
        }
    }
}
