package io.github.mangi.eta.ui.screens.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaPortableBackupControlsTest {
    @Test fun onlyPasswordAndConfirmationAllowDismissal() {
        val controls = PortableBackupControlsState()
        for (phase in PortableBackupPhase.entries) {
            controls.phase = phase
            controls.password = "fixture"
            controls.dismissDialog() // Used by the Cancel action and both dialogs' Back/outside callbacks.
            if (phase in setOf(PortableBackupPhase.EXPORT_PASSWORD, PortableBackupPhase.IMPORT_PASSWORD,
                    PortableBackupPhase.CONFIRM_RESTORE)) {
                assertTrue(phase.dismissible)
                assertEquals(PortableBackupPhase.IDLE, controls.phase)
                assertEquals("", controls.password)
            } else {
                assertFalse(phase.dismissible)
                assertEquals(phase, controls.phase)
            }
        }
    }

    @Test fun validationAndApplyBothBlockBackNavigation() {
        assertEquals(setOf(PortableBackupPhase.INSPECTING, PortableBackupPhase.EXPORTING,
            PortableBackupPhase.PREPARING_RESTORE, PortableBackupPhase.APPLYING_RESTORE),
            PortableBackupPhase.entries.filter { it.locked }.toSet())
    }
}
