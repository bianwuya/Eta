package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeServiceStopTest {
    private fun register(service: AgentRuntimeService, results: MutableList<AgentRuntimeWire.RunResult>): RuntimeRunRegistry.Run {
        val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(service) as RuntimeRunRegistry
        val request = AgentRuntimeWire.RunRequest("service-stop", "inspect",
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList())
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(request.runId, resultSink = results::add,
            beforeTerminalPublish = { registry.finish(request.runId, session) })
        return (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run.also { it.prepared(request) }
    }
    private fun stop(service: AgentRuntimeService, run: RuntimeRunRegistry.Run) {
        AgentRuntimeService::class.java.getDeclaredMethod("cancelRun", String::class.java).apply { isAccessible = true }.invoke(service, run.runId)
    }

    @Test fun normalServiceStopResolvesALateSuccessAsStoppedWithoutEarlyPublication() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val results = mutableListOf<AgentRuntimeWire.RunResult>()
            val run = register(host.get(), results)
            stop(host.get(), run)
            assertTrue(run.session.controller.isCancelled)
            assertFalse(run.session.isTerminal); assertTrue(results.isEmpty())
            var persisted: AgentRuntimeWire.RunResult? = null
            assertTrue(run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "late")) { persisted = it })
            assertFalse(results.single().ok); assertEquals("已停止", results.single().error)
            assertEquals(results.single(), persisted)
        } finally { host.destroy() }
    }

    @Test fun normalServiceStopCannotCancelAResultAlreadyOwnedByCommit() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var worker: Thread? = null
        try {
            val results = mutableListOf<AgentRuntimeWire.RunResult>()
            val run = register(host.get(), results)
            val failure = AtomicReference<Throwable?>()
            worker = Thread {
                try { run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "done")) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                } } catch (t: Throwable) { failure.set(t) }
            }
            worker.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            stop(host.get(), run)
            assertFalse(run.session.controller.isCancelled)
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertNull(failure.get()); assertTrue(results.single().ok)
        } finally { release.countDown(); worker?.join(5000); host.destroy() }
    }
}
