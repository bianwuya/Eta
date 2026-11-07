package io.github.mangi.eta.local.codexusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetCountdownTest {
    @Test fun unknownAndPastResetNeverPretendQuotaHasRecovered() {
        assertEquals("重置时间未知", resetCountdownText(null, 100L))
        assertEquals("重置时间未知", resetCountdownText(0L, 100L))
        assertEquals("已到重置时间 · 额度待刷新", resetCountdownText(100L, 100L))
        assertEquals("已到重置时间 · 额度待刷新", resetCountdownText(100L, 101L))
        assertNull(nextResetCountdownUpdateMillis(null, 100_000L))
        assertNull(nextResetCountdownUpdateMillis(100L, 100_000L))
        assertNull(nextResetCountdownUpdateMillis(100L, 101_000L))
    }

    @Test fun roundsUpMinutesWithoutReachingZeroEarly() {
        assertEquals("1分钟后重置", resetCountdownText(101L, 100L))
        assertEquals("1分钟后重置", resetCountdownText(160L, 100L))
        assertEquals("2分钟后重置", resetCountdownText(161L, 100L))
        assertEquals("1小时后重置", resetCountdownText(3_700L, 100L))
        assertEquals("1小时1分钟后重置", resetCountdownText(3_701L, 100L))
        assertEquals("1天后重置", resetCountdownText(86_500L, 100L))
        assertEquals("1天1小时1分钟后重置", resetCountdownText(90_101L, 100L))
        assertEquals("5小时后重置", resetCountdownText(18_100L, 100L))
        assertEquals("7天后重置", resetCountdownText(604_900L, 100L))
    }

    @Test fun updateDelayFollowsEachWindowsNextDisplayBoundary() {
        assertEquals(1_000L, nextResetCountdownUpdateMillis(161L, 100_000L))
        assertEquals(60_000L, nextResetCountdownUpdateMillis(161L, 101_000L))
        assertEquals(500L, nextResetCountdownUpdateMillis(101L, 100_500L))
        assertEquals(59_500L, nextResetCountdownUpdateMillis(160L, 100_500L))
        assertTrue(nextResetCountdownUpdateMillis(Long.MAX_VALUE, 100_000L)!! in 1L..60_000L)
        assertTrue(!resetCountdownText(Long.MAX_VALUE, 100L).contains('-'))
    }
}
