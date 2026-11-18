package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStatsPopoverTest {
    @Test
    fun viewportShrinksWithAvailableWindowHeight() {
        assertEquals(252.dp, statsPopoverMaxHeight(360.dp))
        assertEquals(132.dp, statsPopoverMaxHeight(200.dp))
        assertEquals(0.dp, statsPopoverMaxHeight(20.dp))
    }

    @Test
    fun tallWindowsStillHaveACappedViewport() {
        assertEquals(480.dp, statsPopoverMaxHeight(1_000.dp))
    }
}
