package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.local.guipresentation.GuiPresentationState
import io.github.mangi.eta.local.guipresentation.GuiVisibilityGate
import java.time.Duration
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeGuiPresentationTest {
    @Test
    fun missingOverlayPermissionIsAnExplicitRejectionNotAPhantomViewReference() {
        ShadowSettings.setCanDrawOverlays(false)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val result = service.invoke("showOverlay") as ToolExecutionDecision.Reject
            assertEquals("GUI_OVERLAY_PERMISSION_REQUIRED", result.code)
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertNull(service.field<ComposeView?>("glowView"))
        } finally { host.destroy() }
    }

    @Test
    fun anAttachedWindowWithoutASettledComposeFrameDoesNotAuthorizeTheAction() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val service = host.get()
            val run = service.register("a")
            val control = service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            service.attachTestCapsule()
            service.setField("capsulePaintedOwner", null)
            val posted = CountDownLatch(1)
            service.setField("visibilityGate", GuiVisibilityGate({ false }, { action ->
                android.os.Handler(Looper.getMainLooper()).post(action)
                posted.countDown()
            }))
            val result = worker.submit<ToolExecutionDecision> {
                control.withAction { generation ->
                    try {
                        AgentRuntimeService::class.java.getDeclaredMethod("awaitGuiPresentation",
                            RuntimeRunRegistry.Run::class.java, AgentRunController::class.java,
                            Function0::class.java, String::class.java).apply { isAccessible = true }
                            .invoke(service, run, run.session.controller, { control.isActionCurrent(generation) }, null)
                            as ToolExecutionDecision
                    } catch (failure: InvocationTargetException) { throw failure.cause!! }
                }
            }
            assertTrue(posted.await(1, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(result.isDone)
            run.session.controller.cancel()
            try { result.get(1, TimeUnit.SECONDS); error("cancelled action must not proceed") }
            catch (failure: ExecutionException) { assertTrue(failure.cause is AgentRunCancelledException) }
        } finally { worker.shutdownNow(); host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    @Config(shadows = [FailingWindowManager::class])
    fun addViewExceptionRejectsAndDoesNotCacheAnUnattachedOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val result = service.invoke("showOverlay") as ToolExecutionDecision.Reject
            assertEquals("GUI_PRESENTATION_FAILED", result.code)
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertNull(service.field<ComposeView?>("glowView"))
        } finally { host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun releasedOwnerKeepsNeutralTouchableTaskControlWithoutGuiOwnership() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            val control = service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val (view, params) = service.attachTestCapsule()
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.RunningTool("tap"), thought = "old action")
            service.applyGuiState()
            control.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
            assertSame(view, service.field<ComposeView>("capsuleView"))
            val state = service.field<MutableState<AgentOverlayState>>("state").value
            assertEquals(AgentOverlayStatus.Reasoning, state.status)
            assertEquals("", state.thought)
            service.setField("capsulePaintedOwner", run.runId)
            service.invoke("updateCapsuleTouchability")
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            service.invoke("toggleCapsule")
            assertTrue(service.field<MutableState<Boolean>>("capsuleExpanded").value)
            service.invoke("toggleCapsule")
            assertFalse(service.field<MutableState<Boolean>>("capsuleExpanded").value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertSame(view, service.field<ComposeView>("capsuleView"))
            service.invoke("requestPause", String::class.java, run.runId)
            assertTrue(run.session.controller.isPaused)
            assertNull(service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
            service.invoke("requestResume")
            assertFalse(run.session.controller.isPaused)
        } finally { host.destroy() }
    }

    @Test
    fun accessibilityReconnectDiscardsAttachedWindowAndOldDrawConfirmation() {
        ShadowSettings.setCanDrawOverlays(true)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller).withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val (old, _) = service.attachTestCapsule()
            val oldEpoch = service.field<Long>("overlayEpoch")
            val oldConnection = AgentAccessibilityService.connection.value
            AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(access.get())
            assertTrue(old.isAttachedToWindow)
            assertFalse(service.invoke("capsuleIsVisible") as Boolean)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(service.field<Long>("overlayEpoch") > oldEpoch)
            assertFalse(service.field<ComposeView?>("capsuleView") === old)
            service.invoke("onAccessibilityConnectionChanged", oldConnection.javaClass, oldConnection)
            assertFalse(service.field<ComposeView?>("capsuleView") === old)
            assertFalse(service.invoke("capsuleIsVisible") as Boolean && service.field<ComposeView?>("capsuleView") === old)
        } finally { host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun disconnectReconnectOfSameServiceNeverReusesOldAttachedView() {
        ShadowSettings.setCanDrawOverlays(true)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller).withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val connect = AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }
            connect.invoke(access.get())
            shadowOf(Looper.getMainLooper()).idle()
            val first = AgentAccessibilityService.connection.value
            val (old, _) = service.attachTestCapsule()
            access.get().onUnbind(null)
            assertFalse(service.invoke("capsuleIsVisible") as Boolean)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<ComposeView?>("capsuleView"))
            connect.invoke(access.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AgentAccessibilityService.connection.value.generation > first.generation)
            assertFalse(service.field<ComposeView?>("capsuleView") === old)
        } finally { host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    @Config(shadows = [ReconnectDuringAdd::class])
    fun connectionChangeDuringWindowCreationRejectsAndLeavesNoStaleWindows() {
        ShadowSettings.setCanDrawOverlays(true)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            ReconnectDuringAdd.onFirstAdd = {
                AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                    .apply { isAccessible = true }.invoke(access.get())
            }
            val service = host.get()
            val result = service.invoke("showOverlay") as ToolExecutionDecision.Reject
            assertEquals("GUI_PRESENTATION_STALE", result.code)
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertNull(service.field<ComposeView?>("glowView"))
        } finally {
            ReconnectDuringAdd.onFirstAdd = null
            host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun terminalTaskDoesNotReturnOnReconnect() {
        ShadowSettings.setCanDrawOverlays(true)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("voice", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
            service.invoke("showInitialTaskControl", RuntimeRunRegistry.Run::class.java, run)
            service.invoke("removeAmbientWindows")
            service.setField("restoreAfterReconnect", true)
            run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "done"))
            AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(access.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<ComposeView?>("capsuleView"))
        } finally { host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun voiceEntryProjectsTaskBeforeFirstGuiAndBackgroundDoesNot() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val background = service.register("background")
            service.invoke("showInitialTaskControl", RuntimeRunRegistry.Run::class.java, background)
            assertNull(service.field<RuntimeRunRegistry.Run?>("displayedRun"))
            val voice = service.register("voice", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
            service.invoke("showInitialTaskControl", RuntimeRunRegistry.Run::class.java, voice)
            assertSame(voice, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            assertNull(service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
            assertTrue(service.field<ComposeView?>("capsuleView") != null)
        } finally { host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun taskPauseDoesNotPauseAnotherOwnersController() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val a = service.register("a")
            val b = service.register("b")
            service.invoke("bindDisplayedRun", RuntimeRunRegistry.Run::class.java, a)
            service.invoke("requestPause", String::class.java, a.runId)
            assertTrue(a.session.controller.isPaused)
            val coordinator = service.field<GuiControlCoordinator>("guiControl")
            val bControl = coordinator.openRun("b", b.session.controller)
            bControl.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(b.session.controller.isPaused)
            assertEquals("b", coordinator.state.value.ownerRunId)
            service.invoke("requestResumeFor", String::class.java, "a")
            assertTrue(a.session.controller.isPaused)
            assertFalse(b.session.controller.isPaused)
            bControl.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(a, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            service.invoke("requestResume")
            assertFalse(a.session.controller.isPaused)
            assertFalse(b.session.controller.isPaused)
            assertNull(coordinator.state.value.ownerRunId)
        } finally { host.destroy() }
    }

    @Test
    fun terminalOfCompetingRunReturnsToOldestStillPausedTask() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val a = service.register("a")
            val b = service.register("b")
            service.invoke("bindDisplayedRun", RuntimeRunRegistry.Run::class.java, a)
            service.invoke("requestPause", String::class.java, a.runId)
            val control = service.field<GuiControlCoordinator>("guiControl").openRun("b", b.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(b, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            control.release()
            b.session.complete(AgentRuntimeWire.RunResult(b.runId, true, "done"))
            shadowOf(Looper.getMainLooper()).idle()
            service.invoke("restorePausedTaskControl")
            assertSame(a, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            assertTrue(a.session.controller.isPaused)
            service.invoke("requestResume")
            assertFalse(a.session.controller.isPaused)
        } finally { host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun multiplePausedTasksResumeInInsertionOrderAndSkipFinished() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val a = service.register("a")
            val b = service.register("b")
            val c = service.register("c")
            service.invoke("bindDisplayedRun", RuntimeRunRegistry.Run::class.java, a)
            service.invoke("requestPause", String::class.java, a.runId)
            service.invoke("bindDisplayedRun", RuntimeRunRegistry.Run::class.java, b)
            service.invoke("requestPause", String::class.java, b.runId)
            val control = service.field<GuiControlCoordinator>("guiControl").openRun("c", c.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            a.session.complete(AgentRuntimeWire.RunResult(a.runId, true, "done"))
            control.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(b, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            assertTrue(b.session.controller.isPaused)
            service.invoke("requestResume")
            assertFalse(b.session.controller.isPaused)
            assertFalse(c.session.controller.isPaused)
        } finally { host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun initialDisconnectedTerminalTaskIsNotRevivedOnReconnect() {
        ShadowSettings.setCanDrawOverlays(false)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("voice", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
            service.invoke("showInitialTaskControl", RuntimeRunRegistry.Run::class.java, run)
            assertTrue(service.field<Boolean>("restoreAfterReconnect"))
            run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "done"))
            AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(access.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<ComposeView?>("capsuleView"))
        } finally { host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun initialDisconnectedVoiceEntryReturnsOnConnectionWithoutAnyTaskEvent() {
        ShadowSettings.setCanDrawOverlays(false)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("voice", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
            service.invoke("showInitialTaskControl", RuntimeRunRegistry.Run::class.java, run)
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertTrue(service.field<Boolean>("restoreAfterReconnect"))
            AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(access.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(service.field<ComposeView?>("capsuleView") != null)
            assertNull(service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
        } finally { host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test fun oldGateDisposeBeforeReconnectDoesNotCancelRestoration() = oldGateDisposeAfterConnectionLoss(false)
    @Test fun oldGateDisposeAfterReconnectDoesNotRemoveReplacement() = oldGateDisposeAfterConnectionLoss(true)

    private fun oldGateDisposeAfterConnectionLoss(reconnectBeforeDispose: Boolean) {
        ShadowSettings.setCanDrawOverlays(true)
        val access = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val connect = AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }
            connect.invoke(access.get())
            val service = host.get()
            val run = service.register("a")
            val coordinator = service.field<GuiControlCoordinator>("guiControl")
            val control = coordinator.openRun("a", run.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            service.attachTestCapsule()
            service.setField("capsulePaintedOwner", null)
            val queue = LinkedBlockingQueue<() -> Unit>()
            service.setField("visibilityGate", GuiVisibilityGate({ false }, { queue.add(it) }, timeoutMs = 80))
            val result = worker.submit<ToolExecutionDecision> {
                control.withAction { generation -> service.awaitPresentation(run) { control.isActionCurrent(generation) } }
            }
            queue.poll(1, TimeUnit.SECONDS)!!.invoke() // actual gate present
            val decision = result.get(1, TimeUnit.SECONDS)
            assertTrue(decision is ToolExecutionDecision.Reject)
            val dispose = queue.poll(1, TimeUnit.SECONDS)!!
            access.get().onUnbind(null)
            service.invoke("onAccessibilityConnectionChanged", AgentAccessibilityService.connection.value.javaClass,
                AgentAccessibilityService.connection.value)
            assertTrue(service.field<Boolean>("restoreAfterReconnect"))
            if (reconnectBeforeDispose) {
                connect.invoke(access.get())
                service.invoke("onAccessibilityConnectionChanged", AgentAccessibilityService.connection.value.javaClass,
                    AgentAccessibilityService.connection.value)
            }
            val replacement = service.field<ComposeView?>("capsuleView")
            dispose.invoke()
            if (reconnectBeforeDispose) {
                assertTrue(replacement != null)
                assertSame(replacement, service.field<ComposeView?>("capsuleView"))
                assertNull(service.field<String?>("blockedPresentationRunId"))
            } else {
                assertTrue(service.field<Boolean>("restoreAfterReconnect"))
                connect.invoke(access.get())
                service.invoke("onAccessibilityConnectionChanged", AgentAccessibilityService.connection.value.javaClass,
                    AgentAccessibilityService.connection.value)
                assertTrue(service.field<ComposeView?>("capsuleView") != null)
            }
        } finally { worker.shutdownNow(); host.destroy(); access.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    fun expiredQueuedGateCannotMakeOwnerEventShowOperationOrCreateWindow() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val service = host.get()
            val run = service.register("a")
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.RunningTool("tap"))
            val control = service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller)
            val queue = LinkedBlockingQueue<() -> Unit>()
            service.setField("visibilityGate", GuiVisibilityGate({ false }, { queue.add(it) }, timeoutMs = 30))
            val result = worker.submit<ToolExecutionDecision> {
                control.withAction { generation -> service.awaitPresentation(run) { control.isActionCurrent(generation) } }
            }
            val present = queue.poll(1, TimeUnit.SECONDS)!!
            assertTrue(result.get(1, TimeUnit.SECONDS) is ToolExecutionDecision.Reject)
            shadowOf(Looper.getMainLooper()).idle() // process owner projection after deadline
            present.invoke()
            queue.poll(1, TimeUnit.SECONDS)!!.invoke()
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertEquals(AgentOverlayStatus.Reasoning, service.field<MutableState<AgentOverlayState>>("state").value.status)
            assertEquals("a", service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
        } finally { worker.shutdownNow(); host.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    private fun AgentRuntimeService.awaitPresentation(run: RuntimeRunRegistry.Run, current: () -> Boolean): ToolExecutionDecision =
        try {
            AgentRuntimeService::class.java.getDeclaredMethod("awaitGuiPresentation", RuntimeRunRegistry.Run::class.java,
                AgentRunController::class.java, Function0::class.java, String::class.java).apply { isAccessible = true }
                .invoke(this, run, run.session.controller, current, null) as ToolExecutionDecision
        } catch (failure: InvocationTargetException) { throw failure.cause!! }

    @Test
    fun reacquiringDuringTailReusesWindowsAndOldRemovalDoesNotRemoveNewOwner() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val a = service.register("a")
            val b = service.register("b")
            val coordinator = service.field<GuiControlCoordinator>("guiControl")
            val first = coordinator.openRun("a", a.session.controller)
            val second = coordinator.openRun("b", b.session.controller)
            first.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val (view, _) = service.attachTestCapsule()
            first.release()
            shadowOf(Looper.getMainLooper()).idle()
            b.overlayState = b.overlayState.copy(status = AgentOverlayStatus.RunningTool("scroll"))
            second.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(view, service.field<ComposeView>("capsuleView"))
            assertSame(b, service.field<RuntimeRunRegistry.Run>("displayedRun"))
            assertEquals(AgentOverlayStatus.Reasoning, service.field<MutableState<AgentOverlayState>>("state").value.status)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertSame(view, service.field<ComposeView>("capsuleView"))
        } finally { host.destroy() }
    }

    @Test
    fun aQueuedAcquiredEventCannotRebindOrRecreateAReleasedRun() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            service.invoke("handleAcceptedRunEvent", RuntimeRunRegistry.Run::class.java, run,
                AgentEvent::class.java, AgentEvent.GuiControlChanged(GuiControlStatus.Acquired))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<RuntimeRunRegistry.Run?>("displayedRun"))
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertEquals(GuiPresentationState.Phase.HIDDEN, service.field<GuiPresentationState>("presentation").phase)
        } finally { host.destroy() }
    }

    @Test
    fun toolFinishedKeepsTheLiveOwnerAndDoesNotTreatOneStepAsTheTaskTerminal() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            service.field<GuiControlCoordinator>("guiControl").openRun("a", run.session.controller).withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val (view, _) = service.attachTestCapsule()
            service.invoke("handleAcceptedRunEvent", RuntimeRunRegistry.Run::class.java, run,
                AgentEvent::class.java, AgentEvent.ToolFinished(1, "call", "tap", "done", 0, 0, success = true))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(run.session.isTerminal)
            assertSame(view, service.field<ComposeView>("capsuleView"))
            assertEquals(GuiPresentationState.Phase.OWNED, service.field<GuiPresentationState>("presentation").phase)
            assertEquals(AgentOverlayStatus.Reasoning, service.field<MutableState<AgentOverlayState>>("state").value.status)
        } finally { host.destroy() }
    }

    @Test
    fun cancelledPausedOwnerStillHasTheExistingResumeProjection() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = host.get()
            val run = service.register("a")
            val coordinator = service.field<GuiControlCoordinator>("guiControl")
            val control = coordinator.openRun("a", run.session.controller)
            control.withAction { }
            shadowOf(Looper.getMainLooper()).idle()
            val (_, params) = service.attachTestCapsule()
            coordinator.pauseForUser("a")
            run.session.controller.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(coordinator.state.value.userPaused)
            assertEquals(AgentOverlayPhase.PAUSED, service.field<MutableState<AgentOverlayState>>("state").value.phase)
            assertEquals(GuiPresentationState.Phase.USER_PAUSED, service.field<GuiPresentationState>("presentation").phase)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
            assertTrue(coordinator.resumeForUser())
        } finally { host.destroy() }
    }

    private fun AgentRuntimeService.attachTestCapsule(): Pair<ComposeView, WindowManager.LayoutParams> {
        invoke("removeAmbientWindows")
        setField("overlayConnection", AgentAccessibilityService.connection.value)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@attachTestCapsule)
            setViewTreeSavedStateRegistryOwner(this@attachTestCapsule)
        }
        val params = WindowManager.LayoutParams().apply {
            width = 200
            height = 80
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
        setField("windowManager", wm)
        setField("capsuleView", view)
        setField("capsuleParams", params)
        setField("capsulePaintedOwner", field<RuntimeRunRegistry.Run>("displayedRun").runId)
        return view to params
    }

    private fun AgentRuntimeService.register(id: String, source: String? = null): RuntimeRunRegistry.Run {
        val registry = field<RuntimeRunRegistry>("runs")
        val request = AgentRuntimeWire.RunRequest(id, "test", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""), emptyList(),
            handoff = source?.let { AgentRuntimeWire.EntryHandoff(id, it, "") })
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(id, beforeTerminalPublish = { registry.finish(id, session) })
        return (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted)
            .run.also { it.prepared(request) }
    }

    private fun AgentRuntimeService.applyGuiState() = invoke("applyGuiControlState", GuiControlCoordinator.State::class.java,
        field<GuiControlCoordinator>("guiControl").state.value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> AgentRuntimeService.field(name: String): T = AgentRuntimeService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(this) as T

    private fun AgentRuntimeService.setField(name: String, value: Any?) = AgentRuntimeService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(this, value)

    private fun AgentRuntimeService.invoke(name: String): Any? = AgentRuntimeService::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(this)

    private fun AgentRuntimeService.invoke(name: String, type: Class<*>, value: Any): Any? =
        AgentRuntimeService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(this, value)

    private fun AgentRuntimeService.invoke(name: String, firstType: Class<*>, first: Any, secondType: Class<*>, second: Any): Any? =
        AgentRuntimeService::class.java.getDeclaredMethod(name, firstType, secondType).apply { isAccessible = true }.invoke(this, first, second)

    @Implements(className = "android.view.WindowManagerImpl")
    class ReconnectDuringAdd {
        companion object { var onFirstAdd: (() -> Unit)? = null }
        @Implementation
        fun addView(@Suppress("UNUSED_PARAMETER") view: View, @Suppress("UNUSED_PARAMETER") params: ViewGroup.LayoutParams) {
            val action = onFirstAdd
            onFirstAdd = null
            action?.invoke()
        }
    }

    @Implements(className = "android.view.WindowManagerImpl")
    class FailingWindowManager {
        @Implementation
        fun addView(@Suppress("UNUSED_PARAMETER") view: View, @Suppress("UNUSED_PARAMETER") params: ViewGroup.LayoutParams) {
            throw WindowManager.BadTokenException("test failure")
        }
    }
}
