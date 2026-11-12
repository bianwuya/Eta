package io.github.mangi.eta.local.subagentstats

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Same bounded records as the detail page; no second usage ledger. */
@Composable
internal fun rememberSubagentSettingsSummary(subagentId: String? = null): String {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var summary by remember { mutableStateOf("正在读取统计…") }
    LaunchedEffect(context, lifecycle, subagentId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val store = withContext(Dispatchers.IO) { SubagentStatsStore.get(context) }
            var loadedRevision = -1L
            while (true) {
                if (loadedRevision != store.revision) {
                    val revision = store.revision
                    val result = withContext(Dispatchers.IO) { runCatching {
                        subagentSettingsSummary(store.records().filter { subagentId == null || it.subagentId == subagentId })
                    } }
                    result.onSuccess { summary = it; loadedRevision = revision }
                        .onFailure { summary = "统计读取失败，原记录保留" }
                }
                delay(3_000) // Existing stats-page refresh cadence; only while this page is resumed.
            }
        }
    }
    return summary
}

internal fun subagentSettingsSummary(records: List<SubagentRequestStat>): String {
    if (records.isEmpty()) return "暂无请求统计；用量与缓存未知"
    val totals = SubagentCacheTotals.from(records)
    val ratio = totals.ratio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "未知"
    fun tokens(value: (SubagentRequestStat) -> Int?): String {
        val known = records.mapNotNull(value)
        val amount = known.sumOf { it.toLong() }.toString()
        return when { known.isEmpty() -> "未知"; known.size < records.size -> "$amount（部分未知）"; else -> amount }
    }
    return "${records.size} 次 · 输入 ${tokens { it.inputTokens }} · 输出 ${tokens { it.outputTokens }}\n" +
        "缓存 ${tokens { it.cachedTokens }} · 命中率 $ratio · 最新模型 ${records.maxBy { it.startedAt }.model}"
}
