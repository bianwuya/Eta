package io.github.mangi.eta.agent.overlay

import android.graphics.Rect
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentOverlayClearanceTest {
    @Test
    fun timedOutMainCallbackCannotYieldOrRestoreALaterCapsule() {
        val host = Host()
        val worker = Executors.newSingleThreadExecutor()
        AgentOverlayClearance.attach(host)
        try {
            // Do not idle main until the bounded wait has expired.
            val result = worker.submit<String> {
                AgentOverlayClearance.around(Rect(1, 1, 2, 2)) { error("unconfirmed clearance cannot inject") }
            }
            try { result.get(2, TimeUnit.SECONDS); error("clearance timeout must reject") }
            catch (failure: ExecutionException) {
                assertEquals("GUI_CLEARANCE_TIMEOUT", (failure.cause as AgentOverlayClearance.Rejected).code)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, host.yields)
            assertEquals(0, host.restores)
        } finally { AgentOverlayClearance.detach(host); worker.shutdownNow() }
    }

    @Test
    fun gestureRestoresOnlyItsOwnPresentationAfterItFinishes() {
        listOf(false, true).forEach { switchOwner ->
            val host = Host()
            val worker = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val injected = CountDownLatch(1)
            val finish = CountDownLatch(1)
            AgentOverlayClearance.attach(host)
            try {
                val result = worker.submit<String> {
                    started.countDown()
                    AgentOverlayClearance.around(Rect(1, 1, 2, 2)) {
                        injected.countDown()
                        assertTrue(finish.await(2, TimeUnit.SECONDS))
                        "done"
                    }
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                // This test drives the paused main looper until the one-shot yield arrives.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (host.yielded.count > 0 && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.yield()
                }
                assertTrue(host.yielded.await(1, TimeUnit.SECONDS))
                assertTrue(injected.await(1, TimeUnit.SECONDS))
                if (switchOwner) host.identity = Any()
                finish.countDown()
                assertEquals("done", result.get(1, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, host.yields)
                assertEquals(if (switchOwner) 0 else 1, host.restores)
            } finally { finish.countDown(); AgentOverlayClearance.detach(host); worker.shutdownNow() }
        }
    }

    private class Host : AgentOverlayClearance.Host {
        @Volatile var identity: Any = Any()
        val yielded = CountDownLatch(1)
        var yields = 0
        var restores = 0
        override fun presentationIdentity(): Any = identity
        override fun capsuleTouchBounds(): Rect = Rect(0, 0, 100, 100)
        override fun yieldCapsule(): Boolean { yields++; yielded.countDown(); return true }
        override fun scheduleCapsuleRestore(delayMs: Long) { restores++ }
        override fun cancelRestore() = Unit
    }
}
