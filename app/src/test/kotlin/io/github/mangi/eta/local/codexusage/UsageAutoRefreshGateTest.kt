package io.github.mangi.eta.local.codexusage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAutoRefreshGateTest {
    @Test fun failedAttemptsAreThrottledWithoutMixingAccountsOrProviders() {
        val gate = UsageAutoRefreshGate()
        assertTrue(gate.allows("provider-a", "account-a", 100L))
        gate.recordAttempt("provider-a", "account-a", 100L)
        assertFalse(gate.allows("provider-a", "account-a", 60_099L))
        assertTrue(gate.allows("provider-a", "account-a", 60_100L))
        assertTrue(gate.allows("provider-a", "account-b", 101L))
        assertTrue(gate.allows("provider-b", "account-a", 101L))
        assertTrue(gate.allows("provider-a", "account-a", 99L))
    }

    @Test fun attemptHistoryIsBoundedAndUpdatedForNewAttempts() {
        val gate = UsageAutoRefreshGate()
        repeat(32) { gate.recordAttempt("provider", "account-$it", 1L) }
        gate.recordAttempt("provider", "account-0", 2L)
        gate.recordAttempt("provider", "account-new", 2L)
        assertFalse(gate.allows("provider", "account-0", 3L))
        assertTrue(gate.allows("provider", "account-1", 3L))
        assertFalse(gate.allows("provider", "account-new", 3L))
    }
}
