package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeMessengerFixtureTest {
    @Before fun resetDatabase() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }
    @After fun closeDatabase() { EtaDatabase.closeForTests() }

    @Test fun fixtureReplacesPendingApplicationBindingBeforeRealMessengerQueries() {
        val bindingStarted = AtomicBoolean()
        val staleContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindingStarted.set(true)
                return true // A different Android stub accepted the bind but never connected.
            }
        }
        val staleResult = CompletableFuture<AgentRuntimeClient.ActiveRunsQuery>()
        val staleWorker = Thread {
            try { staleResult.complete(AgentRuntimeClient(staleContext, AndroidAgentLogger).queryActiveRuns()) }
            catch (failure: Throwable) { staleResult.completeExceptionally(failure) }
        }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!bindingStarted.get() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(bindingStarted.get())
            RuntimeMessengerFixture().use { fixture ->
                fixture.await { staleResult.isDone }
                assertEquals(AgentRuntimeClient.ActiveRunsQuery.Unavailable, staleResult.get())
                val current = fixture.result(fixture.worker { fixture.client.queryActiveRuns() })
                assertTrue(current is AgentRuntimeClient.ActiveRunsQuery.Known)
                assertTrue((current as AgentRuntimeClient.ActiveRunsQuery.Known).runs.isEmpty())
                assertEquals(1, fixture.messages.count { it == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS })
                assertFalse(AgentRuntimeWire.MSG_START_RUN in fixture.messages)
            }
        } finally {
            staleWorker.interrupt()
            staleWorker.join(3000)
            assertFalse(staleWorker.isAlive)
        }
    }
}
