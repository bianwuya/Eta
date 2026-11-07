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
    fun zeroRunTerminalLeaseHasNoRunStopAndOldGlobalActionIsHarmless() {
        val context = RuntimeEnvironment.getApplication()
        var stops = 0
        val id = "terminal:zero-runs"
        assertTrue(AgentExecutionService.acquire(context, id) { stops++ })
        AgentExecutionService.updateRunStatus(context, null, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val host = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(host.get()).lastForegroundNotification)
            assertTrue(notification.actions.isNullOrEmpty())
            assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
            host.get().onStartCommand(android.content.Intent("io.github.mangi.eta.action.STOP_USER_EXECUTION"), 0, 1)
            assertEquals(0, stops)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
        } finally {
            AgentExecutionService.release(id)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            host.destroy()
        }
    }

    @Test
    fun stoppingAndGuiOwnershipBypassNoncriticalNotificationThrottle() {
        val context = RuntimeEnvironment.getApplication()
        val id = "run:notification-transitions"
        assertTrue(AgentExecutionService.acquire(context, id) {})
        val initial = AgentRuntimeWire.ActiveRunSnapshot("notification-transitions", "conversation")
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(initial))
        shadowOf(Looper.getMainLooper()).idle()
        val host = Robolectric.buildService(AgentExecutionService::class.java).create()
        fun current(): Notification = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        try {
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.GeneratingAnswer, 1, listOf(initial))
            shadowOf(Looper.getMainLooper()).idle()
            // No clock advance: stopping must cancel a pending noncritical update and publish now.
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Stopping, 1, listOf(initial))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(context.getString(R.string.overlay_stopping), current().extras.getString(Notification.EXTRA_TEXT))
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Paused, 1,
                listOf(initial.copy(guiStatus = GuiControlStatus.UserPaused, phase = AgentRuntimeWire.RunPhase.Paused)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(context.getString(R.string.task_paused), current().extras.getString(Notification.EXTRA_TEXT))
            assertTrue(current().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally {
            AgentExecutionService.release(id)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            host.destroy()
        }
    }

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
            assertFalse(notification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("私人标题"))
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertNotNull(notification.publicVersion)
            assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("私人标题"))
            assertEquals(1, notification.actions.size)
            assertEquals(context.getString(R.string.task_stop), notification.actions.single().title.toString())
            val stopIntent = shadowOf(notification.actions.single().actionIntent).savedIntent
            assertEquals("notification-single", stopIntent.getStringExtra("run_id"))
            assertEquals("notification-single", stopIntent.data?.pathSegments?.first())
            assertNotNull(stopIntent.getStringExtra("lease_token"))
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
    fun notificationNeverUsesPromptDerivedTitles() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "run:privacy"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val method = AgentExecutionService::class.java.getDeclaredMethod("notification").apply { isAccessible = true }
            listOf("短私密提示", "https://example.invalid/?token=secret", "/storage/private/file.txt", "token=secret").forEach { prompt ->
                val run = AgentRuntimeWire.ActiveRunSnapshot("privacy", "conversation", title = prompt,
                    handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
                AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.HostedToolRunning(prompt), 1, listOf(run))
                shadowOf(Looper.getMainLooper()).idle()
                val notification = method.invoke(controller.get()) as Notification
                val title = notification.extras.getString(Notification.EXTRA_TITLE).orEmpty()
                val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
                assertFalse(title.contains(prompt))
                assertFalse(text.contains(prompt))
            }
        } finally {
            AgentExecutionService.release(leaseId)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun staleStopPendingIntentCannotStopReusedRunLease() {
        val context = RuntimeEnvironment.getApplication()
        val id = "run:reused-notification"
        var stopped = 0
        assertTrue(AgentExecutionService.acquire(context, id) { stopped++ })
        val run = AgentRuntimeWire.ActiveRunSnapshot("reused-notification", "conversation")
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(run))
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val old = shadowOf(requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
                .actions.single().actionIntent).savedIntent
            AgentExecutionService.release(id)
            assertTrue(AgentExecutionService.acquire(context, id) { stopped++ })
            controller.get().onStartCommand(old, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, stopped)
        } finally {
            AgentExecutionService.release(id)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun targetStopDoesNotReleaseLeaseWhileCancellationIsInFlight() {
        val context = RuntimeEnvironment.getApplication()
        val runId = "slow-stop"
        val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(AgentExecutionService.acquire(context, "run:$runId") {
            calls.incrementAndGet(); entered.countDown(); unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)
        })
        assertTrue(AgentExecutionService.acquire(context, "user:terminal") {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1,
            listOf(AgentRuntimeWire.ActiveRunSnapshot(runId, "conversation")))
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val stop = shadowOf(requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
                .actions.single().actionIntent).savedIntent
            controller.get().onStartCommand(stop, 0, 1)
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            controller.get().onStartCommand(stop, 0, 2)
            shadowOf(Looper.getMainLooper()).idle()
            val leases = AgentExecutionService::class.java.getDeclaredField("leases").apply { isAccessible = true }
                .get(null) as ExecutionLeaseRegistry
            assertEquals(2, leases.count())
            assertEquals(1, calls.get())
            unblock.countDown()
            AgentExecutionService.release("run:$runId")
            assertEquals(1, leases.count())
        } finally {
            unblock.countDown()
            AgentExecutionService.release("run:$runId")
            AgentExecutionService.release("user:terminal")
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun multipleRunsSelectExactRunInsteadOfOfferingGlobalStop() {
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
            assertFalse(notification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("A"))
            assertTrue(notification.actions.isNullOrEmpty())
            assertEquals(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_RUN,
                shadowOf(notification.contentIntent).savedIntent.action)
            assertEquals("b", shadowOf(notification.contentIntent).savedIntent
                .getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
        } finally {
            AgentExecutionService.release(leaseId)
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }

    @Test
    fun latestPowerWinsAndTerminalFallsBackWhileOldClickStillTargetsOriginalRun() {
        val context = RuntimeEnvironment.getApplication()
        val ids = listOf("a", "b", "c")
        ids.forEach { assertTrue(AgentExecutionService.acquire(context, "run:$it") {}) }
        assertTrue(AgentExecutionService.acquire(context, "terminal:power-selection") {})
        val a = AgentRuntimeWire.ActiveRunSnapshot("a", "a", createdAt = 1,
            guiStatus = GuiControlStatus.Acquired)
        val b = AgentRuntimeWire.ActiveRunSnapshot("b", "b", createdAt = 2,
            handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            phase = AgentRuntimeWire.RunPhase.Preparing)
        val c = b.copy(runId = "c", conversationId = "c", createdAt = 3, interactionAt = 3,
            phase = AgentRuntimeWire.RunPhase.WaitingForGui)
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, 1, listOf(a))
        shadowOf(Looper.getMainLooper()).idle()
        val host = Robolectric.buildService(AgentExecutionService::class.java).create()
        fun publish(runs: List<AgentRuntimeWire.ActiveRunSnapshot>): Notification {
            AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, runs.size, runs)
            shadowOf(Looper.getMainLooper()).idle()
            return context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        }
        fun target(n: Notification) = shadowOf(n.contentIntent).savedIntent
            .getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID)
        try {
            val selectedB = publish(listOf(a, b))
            val oldClick = selectedB.contentIntent
            assertEquals("b", target(selectedB))
            assertTrue(selectedB.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(context.getString(R.string.task_preparing)))
            assertEquals("b", shadowOf(selectedB.actions.single().actionIntent).savedIntent.getStringExtra("run_id"))
            val selectedC = publish(listOf(a, b, c))
            assertEquals("c", target(selectedC))
            assertTrue(selectedC.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(context.getString(R.string.run_waiting_for_gui)))
            assertEquals("b", target(publish(listOf(a, b))))
            assertEquals("a", target(publish(listOf(a))))
            assertEquals("b", shadowOf(oldClick).savedIntent.getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
            assertEquals(GuiControlStatus.Acquired, a.guiStatus)
        } finally {
            ids.forEach { AgentExecutionService.release("run:$it") }
            AgentExecutionService.release("terminal:power-selection")
            AgentExecutionService.updateRunStatus(context, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            host.destroy()
        }
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
