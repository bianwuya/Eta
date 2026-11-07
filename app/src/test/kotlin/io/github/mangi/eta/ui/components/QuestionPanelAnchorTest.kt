package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionPanelAnchorTest {
    @Test fun frostAndFadeHaveTheSameEightDpContentGap() {
        assertEquals(108.dp, questionPanelBottomInset(124.dp, 24.dp))
        assertEquals(108.dp, questionPanelBottomInset(116.dp, 16.dp))
    }

    @Test fun imeNavigationAttachmentsAndQueueRemainInMeasuredAnchor() {
        val baseline = questionPanelBottomInset(124.dp, 24.dp)
        assertEquals(baseline + 320.dp, questionPanelBottomInset(444.dp, 24.dp))
        assertEquals(baseline + 64.dp + 48.dp, questionPanelBottomInset(236.dp, 24.dp))
    }

    @Test fun firstMeasurementCannotProduceNegativeInsets() {
        assertEquals(8.dp, questionPanelBottomInset(0.dp, 24.dp))
    }
}
