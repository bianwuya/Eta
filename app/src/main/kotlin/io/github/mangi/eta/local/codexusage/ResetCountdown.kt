package io.github.mangi.eta.local.codexusage

/** resetAt is an epoch second supplied by the service, not a window duration. */
internal fun resetCountdownText(resetAt: Long?, nowSeconds: Long): String {
    if (resetAt == null || resetAt <= 0L) return "重置时间未知"
    val remaining = resetAt - nowSeconds.coerceAtLeast(0L)
    if (remaining <= 0L) return "已到重置时间 · 额度待刷新"

    val minutes = (remaining - 1L) / 60L + 1L
    val days = minutes / (24L * 60L)
    val hours = (minutes % (24L * 60L)) / 60L
    val remainder = minutes % 60L
    val duration = buildString {
        if (days > 0L) append("${days}天")
        if (hours > 0L) append("${hours}小时")
        if (remainder > 0L) append("${remainder}分钟")
    }
    return "${duration}后重置"
}

/** Delay until the next displayed minute changes, or null once the reset is due. */
internal fun nextResetCountdownUpdateMillis(resetAt: Long?, nowMillis: Long): Long? {
    if (resetAt == null || resetAt <= 0L) return null
    val safeNow = nowMillis.coerceAtLeast(0L)
    val remaining = resetAt - safeNow / 1000L
    if (remaining <= 0L) return null
    val secondsUntilChange = (remaining - 1L) % 60L + 1L
    return secondsUntilChange * 1000L - safeNow % 1000L
}
