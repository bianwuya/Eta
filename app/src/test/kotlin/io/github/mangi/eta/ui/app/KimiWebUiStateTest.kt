package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWebUiStateTest {
    @Test fun doubleClickAcrossEntrancesStartsOnlyOnceAndRefreshCannotOverrideStarting() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply { onLaunch = { finish.await(); KimiWebLaunchResult.Opened } }
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        controller.launch(TARGET) { error(it) }
        controller.launch(TARGET) { error(it) }
        controller.refresh(TARGET)
        yield()
        assertEquals(1, runtime.launches)
        assertEquals(KimiWebPhase.STARTING, controller.state.value.phase)
        assertTrue(controller.state.value.canStop)
        runtime.status = KimiWebRuntimeStatus(canStop = true, ready = true)
        finish.complete(Unit)
        yield()
        assertEquals(KimiWebPhase.RUNNING, controller.state.value.phase)
    }

    @Test fun switchedTargetCannotInheritLateStatusOrStartFromAnOldCallback() = runBlocking {
        val lateRead = CompletableDeferred<Unit>()
        val other = TARGET.copy(distribution = LinuxDistribution.ALPINE)
        val runtime = FakeRuntime().apply {
            onStatus = { target ->
                if (target == TARGET) withContext(NonCancellable) {
                    lateRead.await(); KimiWebRuntimeStatus(canStop = true, ready = true)
                } else KimiWebRuntimeStatus()
            }
        }
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        controller.select(other)
        controller.refresh(other)
        controller.launch(TARGET) { error(it) }
        lateRead.complete(Unit)
        yield()
        assertEquals(other, controller.state.value.target)
        assertEquals(KimiWebPhase.READY, controller.state.value.phase)
        assertFalse(controller.state.value.canStop)
        assertEquals(0, runtime.launches)
    }

    @Test fun processExitAfterBrowserHandoffIsNotRunningAndFailureIsRetryable() = runBlocking {
        val runtime = FakeRuntime()
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        runtime.status = KimiWebRuntimeStatus(code = "KIMI_EXITED")
        val errors = mutableListOf<String>()
        controller.launch(TARGET) { errors += it }
        yield()
        assertEquals(KimiWebPhase.FAILED, controller.state.value.phase)
        assertTrue(controller.state.value.canLaunch)
        assertFalse(controller.state.value.canStop)
        assertEquals(listOf("KIMI_EXITED"), errors)
    }

    @Test fun reentryAndStopThenOpenUseTheSameStateOwner() = runBlocking {
        val runtime = FakeRuntime().apply { status = KimiWebRuntimeStatus(canStop = true, ready = true) }
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        assertEquals(KimiWebPhase.RUNNING, controller.state.value.phase)
        controller.stop(TARGET) { error(it) }
        yield()
        assertEquals(KimiWebPhase.READY, controller.state.value.phase)
        assertEquals(1, runtime.stops)
        controller.launch(TARGET) { error(it) }
        yield()
        assertEquals(1, runtime.launches)
    }

    @Test fun canceledPreparationLeavesNoStuckBusyLabel() = runBlocking {
        val runtime = FakeRuntime().apply { onLaunch = { throw CancellationException("lease stopped") } }
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        controller.launch(TARGET) { error(it) }
        yield()
        assertEquals(KimiWebPhase.READY, controller.state.value.phase)
        assertTrue(controller.state.value.canLaunch)
    }

    @Test fun aRecoveredStatusClearsAnOldReadFailureWithoutLaunching() = runBlocking {
        val runtime = FakeRuntime().apply { status = KimiWebRuntimeStatus(code = "ROOT_REQUIRED") }
        val controller = KimiWebController(this, runtime)
        controller.select(TARGET)
        controller.refresh(TARGET)
        yield()
        assertEquals(KimiWebPhase.FAILED, controller.state.value.phase)
        runtime.status = KimiWebRuntimeStatus()
        controller.refresh(TARGET)
        yield()
        assertEquals(KimiWebPhase.READY, controller.state.value.phase)
        assertEquals(0, runtime.launches)
    }

    private class FakeRuntime : KimiWebRuntime {
        var launches = 0
        var stops = 0
        var status = KimiWebRuntimeStatus()
        var onStatus: suspend (KimiWebTarget) -> KimiWebRuntimeStatus = { status }
        var onLaunch: suspend () -> KimiWebLaunchResult = { KimiWebLaunchResult.Opened }
        override suspend fun status(target: KimiWebTarget) = onStatus(target)
        override suspend fun launch(target: KimiWebTarget): KimiWebLaunchResult { launches++; return onLaunch() }
        override suspend fun stop(target: KimiWebTarget): KimiWebRuntimeStatus {
            stops++
            status = KimiWebRuntimeStatus()
            return status
        }
    }

    private companion object {
        val TARGET = KimiWebTarget(LinuxDistribution.DEBIAN, LinuxExecutionBackend.PROOT)
    }
}
