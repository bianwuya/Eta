package io.github.mangi.eta.local.guipresentation

import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuiActionPreparationTest {
    @Test
    fun hintIsVisibleBeforePotentialBackAndReconfirmedBeforeAction() {
        val order = mutableListOf<String>()
        val result = GuiActionPreparation.prepare(
            isCurrent = { true },
            ensureVisible = { order += "visible"; ToolExecutionDecision.Allow },
            dismissEntry = { order += "dismiss"; true },
        )
        assertEquals(ToolExecutionDecision.Allow, result)
        assertEquals(listOf("visible", "dismiss", "visible"), order)
    }

    @Test
    fun noBackIsSentWhenVisibilityFailsOrOwnerIsLostWhileWaiting() {
        var dismissed = false
        var current = true
        val denied = ToolExecutionDecision.Reject("GUI_PRESENTATION_FAILED", "not performed")
        assertEquals(denied, GuiActionPreparation.prepare({ current }, { denied }, { dismissed = true; true }))
        val stale = GuiActionPreparation.prepare({ current }, { current = false; ToolExecutionDecision.Allow }, {
            dismissed = true; true
        })
        assertEquals("GUI_PRESENTATION_STALE", (stale as ToolExecutionDecision.Reject).code)
        assertFalse(dismissed)
    }

    @Test
    fun entryFailureDoesNotExecuteOrReconfirmAndPostDismissVisibilityMayStillReject() {
        var confirmations = 0
        val failed = GuiActionPreparation.prepare({ true }, { confirmations++; ToolExecutionDecision.Allow }, { false })
        assertEquals("ENTRY_SURFACE_NOT_READY", (failed as ToolExecutionDecision.Reject).code)
        assertEquals(1, confirmations)
        confirmations = 0
        val secondFailed = GuiActionPreparation.prepare({ true }, {
            if (++confirmations == 1) ToolExecutionDecision.Allow
            else ToolExecutionDecision.Reject("GUI_VISIBILITY_TIMEOUT", "not performed")
        }, { true })
        assertEquals("GUI_VISIBILITY_TIMEOUT", (secondFailed as ToolExecutionDecision.Reject).code)
    }
}
