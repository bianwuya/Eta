package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeNotificationSelectionTest {
    @Test fun powerPriorityDoesNotDependOnDurationGuiOwnerOrInputOrder() {
        val owner = AgentRuntimeWire.ActiveRunSnapshot("owner", "owner", createdAt = 900,
            guiStatus = GuiControlStatus.Acquired)
        val oldPower = AgentRuntimeWire.ActiveRunSnapshot("old-power", "power", createdAt = 1,
            handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
        val newPower = oldPower.copy(runId = "new-power", interactionAt = 2)
        assertEquals("new-power", RuntimeNotificationSelection.select(listOf(newPower, owner, oldPower))!!.runId)
        assertEquals("old-power", RuntimeNotificationSelection.select(listOf(owner, oldPower.copy(interactionAt = 1000), newPower))!!.runId)
        assertEquals("owner", RuntimeNotificationSelection.select(listOf(owner))!!.runId)
        assertNull(RuntimeNotificationSelection.select(emptyList()))
        assertEquals(GuiControlStatus.Acquired, owner.guiStatus)
    }
}
