package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import androidx.compose.runtime.MutableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeScreenOnTest {
    @Test
    fun activeRunSubscriberGetsInitialSnapshotAndOnlyChangedUpdates() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val received = mutableListOf<List<AgentRuntimeWire.ActiveRunSnapshot>>()
            val subscriber = android.os.Messenger(object : android.os.Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: android.os.Message) {
                    if (msg.what == AgentRuntimeWire.MSG_ACTIVE_RUNS_CHANGED) {
                        received += AgentRuntimeWire.activeRunsFromBundle(msg.data)
                    }
                }
            })
            val binder = service.onBind(android.content.Intent(AgentRuntimeWire.ACTION_BIND))!!
            val server = android.os.Messenger(binder)
            server.send(android.os.Message.obtain(null, AgentRuntimeWire.MSG_SUBSCRIBE_ACTIVE_RUNS).apply {
                replyTo = subscriber
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(emptyList<AgentRuntimeWire.ActiveRunSnapshot>()), received)
            service.registerRun("a", ownsGui = false)
            service.invoke("publishExecutionState")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("a", received.last().single().runId)
            val count = received.size
            service.invoke("publishExecutionState")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count, received.size)
            server.send(android.os.Message.obtain(null, AgentRuntimeWire.MSG_UNSUBSCRIBE_ACTIVE_RUNS).apply {
                replyTo = subscriber
            })
            shadowOf(Looper.getMainLooper()).idle()
            service.registerRun("b", ownsGui = false)
            service.invoke("publishExecutionState")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count, received.size)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun pauseResumeAndCancellationUpdateBothAppAndGuiWindowState() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = service.registerRun("active-run").session
            val capsule = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
            }
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                width = 200
                height = 80
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            wm.addView(capsule, params)
            service.setField("windowManager", wm)
            service.setField("capsuleView", capsule)
            service.setField("capsuleParams", params)

            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            service.invokeForRun("requestPause", session.runId)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            session.controller.cancel()
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun completedRunStopsKeepingScreenOnBeforeServiceDestruction() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = service.registerRun("completed-run").session
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            session.complete(AgentRuntimeWire.RunResult(session.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun destroyingServiceClearsActiveRunScreenRequest() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.registerRun("destroyed-run")
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
        assertFalse(AgentRuntimeService.keepScreenOn.value)
    }

    @Test
    fun backgroundCompletionDoesNotClearGuiOwnersScreenRequest() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.registerRun("gui-run")
            val background = service.registerRun("background-run", ownsGui = false)
            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun cancelledPausedOwnerKeepsTheResumeControlAvailable() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val run = service.registerRun("paused-run")
            service.invokeForRun("requestPause", run.runId)
            run.session.controller.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(service.field<GuiControlCoordinator>("guiControl").state.value.userPaused)
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            service.invoke("requestResume")
            assertFalse(service.field<GuiControlCoordinator>("guiControl").state.value.userPaused)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun executionStatusFollowsRecentInteractionWithoutGivingSelectedBackgroundRunScreenControl() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            service.assertExecutionStatus(background.overlayState.status, 1, background.runId)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            val guiRun = service.registerRun("gui-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.GeneratingAnswer)
            }
            val registry = service.field<RuntimeRunRegistry>("runs")
            assertTrue(guiRun.snapshot().interactionAt > background.snapshot().interactionAt)
            service.assertExecutionStatus(guiRun.overlayState.status, 2, guiRun.runId)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            val guiControl = guiCoordinator.openRun(guiRun.runId, guiRun.session.controller)
            guiControl.withAction { }
            service.assertExecutionStatus(guiRun.overlayState.status, 2, guiRun.runId)
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            assertTrue(registry.preferRun(background.runId))
            service.assertExecutionStatus(background.overlayState.status, 2, background.runId)
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertFalse(background.session.controller.isPaused)
            assertFalse(background.session.controller.isCancelled)
            assertFalse(guiRun.session.controller.isCancelled)

            assertTrue(registry.preferRun(guiRun.runId))
            service.assertExecutionStatus(guiRun.overlayState.status, 2, guiRun.runId)
            guiControl.release()
            service.assertExecutionStatus(guiRun.overlayState.status, 2, guiRun.runId)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            guiRun.session.complete(AgentRuntimeWire.RunResult(guiRun.runId, true, "完成"))
            service.assertExecutionStatus(background.overlayState.status, 1, background.runId)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.assertExecutionStatus(null, 0, null)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun retainedGuiPauseDoesNotPauseBackgroundNotificationOrKeepFinishedRunsPromoted() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val terminalLeaseId = "terminal:screen-status"
        var executionController: org.robolectric.android.controller.ServiceController<AgentExecutionService>? = null
        try {
            val service = serviceController.get()
            assertTrue(AgentExecutionService.acquire(service, terminalLeaseId) {})
            executionController = Robolectric.buildService(AgentExecutionService::class.java).create()
            val guiRun = service.registerRun("paused-run")
            service.invokeForRun("requestPause", guiRun.runId)
            service.assertExecutionStatus(AgentOverlayStatus.Paused, 1, guiRun.runId)
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(guiCoordinator.state.value.userPaused)
            assertTrue(guiRun.session.controller.isPaused)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            assertTrue(background.snapshot().interactionAt > guiRun.snapshot().interactionAt)
            service.assertExecutionStatus(background.overlayState.status, 2, background.runId)
            val selectedNotification = service.getSystemService(NotificationManager::class.java)
                .activeNotifications.single().notification
            assertEquals(background.runId, shadowOf(selectedNotification.contentIntent).savedIntent
                .getStringExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(guiCoordinator.state.value.userPaused)
            assertTrue(guiRun.session.controller.isPaused)
            assertFalse(background.session.controller.isPaused)
            assertFalse(background.session.controller.isCancelled)
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            guiRun.session.controller.cancel()
            guiRun.session.complete(AgentRuntimeWire.RunResult(guiRun.runId, true, "完成"))
            service.assertExecutionStatus(background.overlayState.status, 1, background.runId)
            assertTrue(guiCoordinator.state.value.userPaused)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.assertExecutionStatus(null, 0, null)
            assertTrue(guiCoordinator.state.value.userPaused)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            val notification = service.getSystemService(NotificationManager::class.java)
                .activeNotifications.single().notification
            assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(notification.extras.containsKey("android.shortCriticalText"))
            assertTrue(notification.actions.isNullOrEmpty())
            assertFalse(shadowOf(notification.contentIntent).savedIntent
                .hasExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_RUN_ID))
            val leases = AgentExecutionService::class.java.getDeclaredField("leases").apply {
                isAccessible = true
            }.get(null) as ExecutionLeaseRegistry
            assertEquals(1, leases.count())
            assertTrue(leases.token(terminalLeaseId) != null)
        } finally {
            AgentExecutionService.release(terminalLeaseId)
            serviceController.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            executionController?.destroy()
        }
    }

    private fun AgentRuntimeService.assertExecutionStatus(
        status: AgentOverlayStatus?,
        activeRunCount: Int,
        selectedRunId: String?,
    ) {
        invoke("publishExecutionState")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(status, AgentExecutionService::class.java.getDeclaredField("runStatus").apply {
            isAccessible = true
        }.get(null))
        assertEquals(activeRunCount, AgentExecutionService::class.java.getDeclaredField("activeRunCount").apply {
            isAccessible = true
        }.getInt(null))
        @Suppress("UNCHECKED_CAST")
        val snapshots = AgentExecutionService::class.java.getDeclaredField("activeRunSnapshots").apply {
            isAccessible = true
        }.get(null) as List<AgentRuntimeWire.ActiveRunSnapshot>
        assertEquals(activeRunCount, snapshots.size)
        assertEquals(selectedRunId, RuntimeNotificationSelection.select(snapshots)?.runId)
    }

    private fun AgentRuntimeService.registerRun(runId: String, ownsGui: Boolean = true): RuntimeRunRegistry.Run {
        val registry = field<RuntimeRunRegistry>("runs")
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(runId, beforeTerminalPublish = { registry.finish(runId, session) })
        val request = AgentRuntimeWire.RunRequest(
            runId, "测试", AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            emptyList(),
        )
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
        run.prepared(request)
        if (ownsGui) field<GuiControlCoordinator>("guiControl").openRun(runId, session.controller).withAction { }
        shadowOf(Looper.getMainLooper()).idle()
        return run
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> AgentRuntimeService.field(name: String): T = AgentRuntimeService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(this) as T

    private fun AgentRuntimeService.invokeForRun(name: String, runId: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name, String::class.java).apply {
            isAccessible = true
        }.invoke(this, runId)
    }

    private fun AgentRuntimeService.setField(name: String, value: Any) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(this, value)
    }

    private fun AgentRuntimeService.invoke(name: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(this)
    }
}
