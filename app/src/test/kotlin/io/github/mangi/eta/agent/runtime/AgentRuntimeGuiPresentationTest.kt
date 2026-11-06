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
    fun releasedOwnerHasNeutralUntouchableTailAndLateClearanceRestoreCannotReactivateIt() {
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
            service.invoke("setCapsuleInputMode", Boolean::class.javaPrimitiveType!!, true)
            control.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(service.field<GuiControlCoordinator>("guiControl").state.value.ownerRunId)
            assertSame(view, service.field<ComposeView>("capsuleView"))
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            val state = service.field<MutableState<AgentOverlayState>>("state").value
            assertEquals(AgentOverlayStatus.Reasoning, state.status)
            assertEquals("", state.thought)
            service.invoke("setCapsuleYielded", Boolean::class.javaPrimitiveType!!, false)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertNull(service.field<ComposeView?>("capsuleView"))
            assertFalse(view.isAttachedToWindow)
        } finally { host.destroy() }
    }

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
            assertEquals(b.overlayState, service.field<MutableState<AgentOverlayState>>("state").value)
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

    private fun AgentRuntimeService.register(id: String): RuntimeRunRegistry.Run {
        val registry = field<RuntimeRunRegistry>("runs")
        val request = AgentRuntimeWire.RunRequest(id, "test", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""), emptyList())
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
    class FailingWindowManager {
        @Implementation
        fun addView(@Suppress("UNUSED_PARAMETER") view: View, @Suppress("UNUSED_PARAMETER") params: ViewGroup.LayoutParams) {
            throw WindowManager.BadTokenException("test failure")
        }
    }
}
