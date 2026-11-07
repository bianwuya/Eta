package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiControlCoordinatorTest {
    @Test
    fun visibilityLeaseIsValidOnlyInsideItsCurrentActionAndReleaseIsImmediate() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("a", AgentRunController())
        var generation = -1L
        control.withAction {
            generation = it
            assertTrue(control.isActionCurrent(it))
        }
        assertFalse(control.isActionCurrent(generation))
        control.withAction {
            assertTrue(control.isActionCurrent(it))
            control.release()
            assertFalse(control.isActionCurrent(it))
        }
        assertNull(coordinator.state.value.ownerRunId)
        coordinator.openRun("b", AgentRunController()).use { next ->
            next.withAction { assertTrue(next.isActionCurrent(it)) }
        }
        control.close()
    }

    @Test
    fun pauseResumeAndCancellationInvalidateQueuedVisibilityCallbacksEvenForTheSameOwner() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val control = coordinator.openRun("a", controller)
        control.withAction { old ->
            assertTrue(control.isActionCurrent(old))
            coordinator.pauseForUser("a")
            assertFalse(control.isActionCurrent(old))
            coordinator.resumeForUser()
            assertFalse(control.isActionCurrent(old))
        }
        control.withAction { current ->
            assertTrue(control.isActionCurrent(current))
            controller.cancel()
            assertFalse(control.isActionCurrent(current))
        }
        assertNull(coordinator.state.value.ownerRunId)
    }

    @Test
    fun ownershipSpansActionsAndWaitersEnterInOrderAfterRelease() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bQueued = CountDownLatch(1)
        val cQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val c = coordinator.openRun("c", AgentRunController()) { if (it == GuiControlStatus.Waiting) cQueued.countDown() }
        val order = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstGeneration = a.withAction { it }
            val bResult = pool.submit { b.withAction { order += "b"; b.release() } }
            await(bQueued)
            val cResult = pool.submit { c.withAction { order += "c"; c.release() } }
            await(cQueued)
            assertEquals(firstGeneration, a.withAction { it })
            assertTrue(order.isEmpty())
            a.release()
            bResult.get(2, TimeUnit.SECONDS)
            cResult.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("b", "c"), order)
            assertNull(coordinator.state.value.ownerRunId)
        } finally {
            a.close(); b.close(); c.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellationKeepsOwnershipUntilTheCurrentActionExits() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val aResult = pool.submit { a.withAction { entered.countDown(); await(drain) } }
            await(entered)
            val bResult = pool.submit { b.withAction { } }
            await(bQueued)
            controller.cancel()
            assertEquals("a", coordinator.state.value.ownerRunId)
            assertFalse(bResult.isDone)
            drain.countDown()
            aResult.get(2, TimeUnit.SECONDS)
            bResult.get(2, TimeUnit.SECONDS)
            assertEquals("b", coordinator.state.value.ownerRunId)
        } finally {
            drain.countDown()
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancelledWaiterCannotEnterOrBlockTheNextWaiter() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bController = AgentRunController()
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", bController) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            a.withAction { }
            val result = pool.submit { b.withAction { error("已取消的等待者不能执行") } }
            await(bQueued)
            bController.cancel()
            try {
                result.get(2, TimeUnit.SECONDS)
                error("应传播取消")
            } catch (failure: ExecutionException) {
                assertTrue(failure.cause is AgentRunCancelledException)
            }
            a.release()
            coordinator.openRun("c", AgentRunController()).use { c ->
                c.withAction { assertEquals("c", coordinator.state.value.ownerRunId) }
            }
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun userPauseSurvivesOwnerCancellationUntilExplicitResume() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val initialGeneration = a.withAction { it }
        val queued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) queued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(coordinator.pauseForUser("a"))
            assertTrue(coordinator.state.value.userPaused)
            val bResult = pool.submit<Long> { b.withAction { it } }
            await(queued)
            controller.cancel()
            assertNull(coordinator.state.value.ownerRunId)
            assertTrue(coordinator.state.value.userPaused)
            assertFalse(bResult.isDone)
            assertTrue(coordinator.resumeForUser())
            assertTrue(bResult.get(2, TimeUnit.SECONDS) > initialGeneration)
            assertFalse(coordinator.state.value.userPaused)
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun releasingDuringUserPauseDoesNotLeaveTheOriginalRunPermanentlyPaused() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        coordinator.openRun("a", controller).use { a ->
            a.withAction { }
            coordinator.pauseForUser("a")
            a.release()
            assertTrue(controller.isPaused)
            assertTrue(coordinator.state.value.userPaused)
            assertNull(coordinator.state.value.ownerRunId)
            coordinator.resumeForUser()
            assertFalse(controller.isPaused)
            assertFalse(coordinator.state.value.userPaused)
        }
    }

    @Test
    fun pauseIsPublishedOnlyAfterTheActionDrainsAndResumeInvalidatesObservationGeneration() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Long> { a.withAction { entered.countDown(); await(drain); it } }
            await(entered)
            assertTrue(coordinator.pauseForUser("a"))
            assertFalse(coordinator.state.value.userPaused)
            drain.countDown()
            val oldGeneration = result.get(2, TimeUnit.SECONDS)
            assertTrue(coordinator.state.value.userPaused)
            assertTrue(coordinator.resumeForUser())
            assertTrue(a.withAction { it } > oldGeneration)
        } finally {
            drain.countDown()
            a.close()
            pool.shutdownNow()
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("并发步骤未到达", latch.await(2, TimeUnit.SECONDS))
    }
}
