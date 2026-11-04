package io.github.mangi.eta.local.subagentstats

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.datastore.SettingsDataStore
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SubagentStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<SubagentRequestStat>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.subagentSettingsFlow().collectAsState(initial = SubagentSettings())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val store = withContext(Dispatchers.IO) { SubagentStatsStore.get(context) }
            var loadedRevision = -1L
            while (true) {
                if (loadedRevision != store.revision) {
                    val targetRevision = store.revision
                    val loaded = withContext(Dispatchers.IO) { runCatching {
                        store.records() to EtaDatabase.get(context).conversationDao()
                            .conversationIdentities().associate { it.id to it.title }
                    } }
                    loaded.onSuccess { (rows, names) ->
                        records = rows; titles = names; loadError = false; loadedRevision = targetRevision
                    }.onFailure { loadError = true }
                }
                delay(3_000)
            }
        }
    }
    BackHandler { if (selected != null) selected = null else onBack() }
    val visible = remember(records, selected) { records.filter { selected == null || it.conversationId == selected } }
    var groups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var modelTotals by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var visibleTotals by remember { mutableStateOf("") }
    LaunchedEffect(records, selected) {
        val projected = withContext(Dispatchers.Default) {
            Triple(records.groupBy { it.conversationId }.map { (id, rows) ->
                id to (totalsText(rows) + "\n" + rows.map { it.model }.distinct().joinToString(" · "))
            }, visible.groupBy { it.model }.map { (model, rows) -> model to totalsText(rows) }, totalsText(visible))
        }
        groups = projected.first; modelTotals = projected.second; visibleTotals = projected.third
    }
    val exportRecords by rememberUpdatedState(visible)
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val snapshot = exportRecords
            val result = withContext(Dispatchers.IO) { runCatching {
                val stream = requireNotNull(context.contentResolver.openOutputStream(uri))
                stream.bufferedWriter().use { it.write(SubagentStatsStore.export(snapshot)) }
            } }
            exportStatus = if (result.isSuccess) "已导出 ${snapshot.size} 条统计" else "导出失败，请重试"
        }
    }
    MiuixScaffoldPage(
        title = if (selected == null) "子代理请求统计" else "会话请求明细",
        onBack = { if (selected != null) selected = null else onBack() },
        actions = {
            TextButton(text = "导出", onClick = { export.launch("eta-subagent-stats.json") })
        },
    ) {
        item(key = "collection") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = "采集请求统计",
                    summary = "默认关闭。关闭后跳过新请求的解析、指纹和写库；在途记录正常收尾，历史仍可查看。重新开启建立新对照。",
                    checked = settings.collectRequestStats,
                    onCheckedChange = { enabled -> scope.launch {
                        SettingsDataStore.setRequestStatsEnabled(enabled)
                    } },
                )
                TextButton(text = "清除历史统计", onClick = { scope.launch {
                    runCatching { withContext(Dispatchers.IO) { SubagentStatsStore.get(context).clear() } }
                        .onSuccess { records = emptyList() }.onFailure { loadError = true }
                } })
            }
        }
        item(key = "explanation") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("按会话归类，每次模型尝试分别记录；保留最近 5000 条。仅统计安装此版本之后的请求，包含失败与重试。",
                    style = MiuixTheme.textStyles.footnote1)
                Text("缓存数字来自服务商响应，未知不等于零。命中率 = 有完整用量记录的缓存 token ÷ 对应输入 token；不混入未知样本。",
                    style = MiuixTheme.textStyles.footnote1)
                Text("指纹来自实际发送的 JSON。旧消息完全保留但缓存下降，只能排除已检查的 Eta 前缀变化，不能单凭它断言中转故障。",
                    style = MiuixTheme.textStyles.footnote1)
                exportStatus?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                if (loadError) Text("统计读取失败，请稍后重试。")
                if (records.isEmpty()) Text("暂无统计。仅在开启采集后记录正常请求，无需额外测试请求。")
            }
        }
        if (selected == null) {
            items(groups, key = { it.first }) { (conversationId, summary) ->
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = titles[conversationId] ?: conversationId,
                        summary = summary,
                        onClick = { selected = conversationId },
                    )
                }
            }
        } else {
            item(key = "summary") {
                SmallTitle(titles[selected] ?: selected.orEmpty())
                Text(visibleTotals, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(modelTotals, key = { "model-${it.first}" }) { (model, summary) ->
                Text("$model · $summary", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MiuixTheme.textStyles.footnote1)
            }
            items(visible, key = { it.id }) { record ->
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${time(record.startedAt)} · ${record.subagentId} · ${record.model}",
                            style = MiuixTheme.textStyles.body2)
                        Text("${record.profile} · 第 ${record.ordinal} 次 · ${status(record.status)} · ${record.purpose}" +
                            (if (record.resumed) " · 续接" else "") +
                            (if (record.contextSummaryCount > 0) " · 历史含压缩摘要" else ""),
                            style = MiuixTheme.textStyles.footnote1)
                        Text("输入 ${number(record.inputTokens)} · 缓存 ${number(record.cachedTokens)} · 输出 ${number(record.outputTokens)}")
                        val inputDelta = record.inputTokens?.let { current -> record.previousInputTokens?.let { current - it } }
                        if (inputDelta != null) Text("相对上次输入 ${if (inputDelta >= 0) "+" else ""}$inputDelta token",
                            style = MiuixTheme.textStyles.footnote1)
                        Text("历史：${history(record.historyChange)} · 消息 ${record.previousMessageCount ?: "—"} → ${record.messageCount ?: "未知"}",
                            style = MiuixTheme.textStyles.footnote1)
                        Text("旧消息相同前缀 ${record.commonPrefixMessages ?: "—"} 条 · 请求 ${record.previousRequestBytes ?: "—"} → ${record.requestBytes ?: "未知"} 字节",
                            style = MiuixTheme.textStyles.footnote1)
                        Text("前缀/配置变化：${record.changedFields.joinToString { fieldLabel(it) }.ifBlank { if (record.baselineId == null) "首次，无对照" else "未发现" }}",
                            style = MiuixTheme.textStyles.footnote1)
                        Text("HTTP ${record.httpCode ?: "未知"} · ${record.httpAttempts} 次发送 · ${record.elapsedMs / 1000.0} 秒" +
                            record.errorCode?.let { " · $it" }.orEmpty(), style = MiuixTheme.textStyles.footnote1)
                        if (record.historyChange == "unavailable") Text("此协议尚未取得可比较的历史指纹，不能据此归因。",
                            style = MiuixTheme.textStyles.footnote1)
                        Text("${record.host}${record.path}\n请求 ${record.id.take(8)} · 上次 ${record.baselineId?.take(8) ?: "—"}" +
                            record.requestId?.let { "\n服务端请求 ID $it" }.orEmpty(),
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}

private fun totalsText(records: List<SubagentRequestStat>): String {
    val total = SubagentCacheTotals.from(records)
    val ratio = total.ratio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "未知"
    return "${total.requests} 次 · 命中率 $ratio · 明确零 ${total.zeroCache} 次 · 未知 ${total.missingCache} 次\n" +
        "有效配对 ${total.knownPairs} 次：输入 ${total.input} / 缓存 ${total.cached}"
}
private fun number(value: Int?): String = value?.toString() ?: "未知"
private fun time(value: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(value))
private fun status(value: String): String = when (value) {
    "running" -> "已发起/终态未记录"
    "completed" -> "完成"
    "cancelled" -> "取消"
    else -> "失败"
}
private fun history(value: String): String = when (value) {
    "first" -> "首次请求"
    "identical" -> "旧消息相同"
    "append" -> "旧消息相同，仅追加"
    "shorter" -> "消息减少"
    "prefix_changed" -> "旧消息发生变化"
    else -> "未知"
}

private fun fieldLabel(value: String): String = when (value) {
    "system" -> "系统提示"
    "tools" -> "工具目录"
    "parameters" -> "模型/思考等参数"
    "cache_key" -> "缓存键"
    "endpoint" -> "服务地址"
    else -> value
}
