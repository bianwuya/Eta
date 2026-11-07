package io.github.mangi.eta.local.guipresentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiPresentationStateTest {
    @Test
    fun releaseHasAnUntouchableFiveHundredMillisecondVisualTail() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        assertTrue(state.touchable)
        state.update(null, false, 10)
        assertEquals(GuiPresentationState.Phase.RELEASING, state.phase)
        assertEquals("a", state.displayedRunId)
        assertFalse(state.touchable)
        assertFalse(state.expire(state.revision, 509))
        assertTrue(state.expire(state.revision, 510))
        assertNull(state.displayedRunId)
    }

    @Test
    fun reacquisitionAndOwnerSwitchInvalidateTheOldRemovalCallback() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.update(null, false, 10)
        val old = state.revision
        state.update("b", false, 20)
        assertEquals("b", state.displayedRunId)
        assertTrue(state.touchable)
        assertFalse(state.expire(old, 1_000))
        state.update(null, false, 30)
        val releaseB = state.revision
        state.update("b", false, 40)
        assertFalse(state.expire(releaseB, 1_000))
    }

    @Test
    fun repeatedNullDoesNotExtendTheDeadlineAndStaleTerminalCannotHideNewOwner() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.update(null, false, 10)
        val revision = state.revision
        state.update(null, false, 100)
        assertEquals(510L, state.releaseDeadlineMs)
        assertEquals(revision, state.revision)
        state.update("b", false, 200)
        state.terminal("a")
        assertEquals("b", state.displayedRunId)
        state.terminal("b")
        assertEquals(GuiPresentationState.Phase.HIDDEN, state.phase)
    }

    @Test
    fun terminalDuringVisualTailInvalidatesDelayedRemoval() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.update(null, false, 10)
        val revision = state.revision
        state.terminal("a")
        assertFalse(state.expire(revision, 900))
        assertEquals(GuiPresentationState.Phase.HIDDEN, state.phase)
    }

    @Test
    fun delayedClearanceRestoreNeverMakesReleasedWindowsTouchable() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.setYielded(true)
        assertFalse(state.touchable)
        state.update(null, false, 10)
        state.setYielded(false)
        assertFalse(state.touchable)
    }

    @Test
    fun cancelledPausedOwnerRetainsResumeControlUntilUserResumes() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.update("a", true, 10)
        state.update(null, true, 20)
        assertEquals("a", state.displayedRunId)
        assertEquals(GuiPresentationState.Phase.USER_PAUSED, state.phase)
        assertTrue(state.touchable)
        assertNull(state.releaseDeadlineMs)
        state.update(null, false, 30)
        assertFalse(state.touchable)
    }

    @Test
    fun onlyAnAlreadyVisibleSameOwnerMayUseClearanceAndMaterializePaths() {
        val state = GuiPresentationState()
        state.update("a", false, 0)
        state.setYielded(true)
        assertFalse(state.hasControlledPresentation("a", false))
        state.confirmVisible("a")
        assertTrue(state.hasControlledPresentation("a", false))
        state.setYielded(false)
        assertTrue(state.hasControlledPresentation("a", true))
        assertFalse(state.hasControlledPresentation("a", false))
        state.update("a", false, 1)
        assertTrue(state.hasControlledPresentation("a", true))
        state.update("b", false, 2)
        assertFalse(state.hasControlledPresentation("b", true))
        state.confirmVisible("a")
        assertFalse(state.hasControlledPresentation("b", true))
        state.confirmVisible("b")
        state.update(null, false, 3)
        state.update("b", false, 4)
        assertFalse(state.hasControlledPresentation("b", true))
    }
}
