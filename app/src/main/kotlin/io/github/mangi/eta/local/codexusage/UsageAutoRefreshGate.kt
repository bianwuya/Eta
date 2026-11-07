package io.github.mangi.eta.local.codexusage

/** UI-only attempt history; manual refresh always remains available. */
internal class UsageAutoRefreshGate {
    private val attempts = LinkedHashMap<Pair<String, String>, Long>()

    fun allows(providerId: String, accountId: String, nowMillis: Long): Boolean {
        val previous = attempts[providerId to accountId] ?: return true
        return nowMillis < previous || nowMillis - previous >= 60_000L
    }

    fun recordAttempt(providerId: String, accountId: String, nowMillis: Long) {
        val key = providerId to accountId
        attempts.remove(key)
        attempts[key] = nowMillis
        if (attempts.size > 32) attempts.remove(attempts.keys.first())
    }
}
