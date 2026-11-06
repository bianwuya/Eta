package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
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
    fun pauseResumeAndCancellationUpdateBothAppAndGuiWindowState() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = service.registerRun("active-run").session
            val capsule = ComposeView(service)
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
    fun executionStatusFollowsGuiOwnershipWithoutGivingBackgroundRunsScreenControl() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            service.assertExecutionStatus(background.overlayState.status, 1)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            val guiRun = service.registerRun("gui-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.GeneratingAnswer)
            }
            val guiControl = guiCoordinator.openRun(guiRun.runId, guiRun.session.controller)
            guiControl.withAction { }
            service.assertExecutionStatus(AgentOverlayStatus.GeneratingAnswer, 2)
            assertEquals(guiRun.runId, guiCoordinator.state.value.ownerRunId)
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            guiControl.release()
            service.assertExecutionStatus(background.overlayState.status, 2)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun retainedGuiPauseDoesNotPauseBackgroundNotificationOrKeepFinishedRunsPromoted() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val guiRun = service.registerRun("paused-run")
            service.invokeForRun("requestPause", guiRun.runId)
            val background = service.registerRun("background-run", ownsGui = false).apply {
                overlayState = overlayState.copy(status = AgentOverlayStatus.RunningTool("terminal_job"))
            }
            service.assertExecutionStatus(AgentOverlayStatus.Paused, 2)

            guiRun.session.controller.cancel()
            guiRun.session.complete(AgentRuntimeWire.RunResult(guiRun.runId, true, "完成"))
            service.assertExecutionStatus(background.overlayState.status, 1)
            val guiCoordinator = service.field<GuiControlCoordinator>("guiControl")
            assertTrue(guiCoordinator.state.value.userPaused)
            assertNull(guiCoordinator.state.value.ownerRunId)
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            background.session.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.assertExecutionStatus(null, 0)
            assertTrue(guiCoordinator.state.value.userPaused)
        } finally {
            serviceController.destroy()
        }
    }

    private fun AgentRuntimeService.assertExecutionStatus(status: AgentOverlayStatus?, activeRunCount: Int) {
        invoke("publishExecutionState")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(status, AgentExecutionService::class.java.getDeclaredField("runStatus").apply {
            isAccessible = true
        }.get(null))
        assertEquals(activeRunCount, AgentExecutionService::class.java.getDeclaredField("activeRunCount").apply {
            isAccessible = true
        }.getInt(null))
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
