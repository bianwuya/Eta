package io.github.mangi.eta.local.subagentstats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.*
import com.itos.coui.layout.Group
import com.itos.coui.layout.Footer
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SubagentStatsScreen(
    onBack: () -> Unit,
    conversationId: String? = null,
    onOpenConversation: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<SubagentRequestStat>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val selected = conversationId
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsFlow = remember { SettingsDataStore.subagentSettingsFlow() }
    val settings by settingsFlow.collectAsState(initial = SubagentSettings())
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
    var confirmClear by remember { mutableStateOf(false) }
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
    StatsPage(
        title = if (selected == null) "子代理请求统计" else "会话请求明细",
        onBack = onBack,
        actions = {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiTopAppBarIconButton(
                icon = Icons.Rounded.FileDownload, contentDescription = "导出请求统计", enabled = visible.isNotEmpty(),
                onClick = { export.launch("eta-subagent-stats.json") })
            else IconButton(onClick = { export.launch("eta-subagent-stats.json") }, enabled = visible.isNotEmpty()) {
                Icon(Icons.Rounded.FileDownload, contentDescription = "导出请求统计")
            }
        },
        overlay = {
            val clearRecords = {
                confirmClear = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { SubagentStatsStore.get(context).clear() } }
                        .onSuccess { records = emptyList() }.onFailure { loadError = true }
                }
                Unit
            }
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiConfirmDialog(
                visible = confirmClear, title = "清除历史统计", message = "删除本机保留的全部请求统计，不影响聊天记录。",
                confirmText = "清除", destructive = true, onConfirm = clearRecords, onDismissRequest = { confirmClear = false })
            else WindowDialog(show = confirmClear, title = "清除历史统计", onDismissRequest = { confirmClear = false }) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("删除本机保留的全部请求统计，不影响聊天记录。")
                    MiuixDialogActions(confirmText = "清除", destructive = true,
                        onCancel = { confirmClear = false }, onConfirm = clearRecords)
                }
            }
        },
    ) {
        if (selected == null) {
            item(key = "collection") {
                StatsGroup("采集设置") {
                    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiSwitchPreference(
                        title = "采集请求统计", summary = "默认关闭，历史记录仍可查看", checked = settings.collectRequestStats,
                        onCheckedChange = { enabled -> scope.launch { SettingsDataStore.setRequestStatsEnabled(enabled) } })
                    else SwitchPreference(
                        title = "采集请求统计",
                        summary = "默认关闭，历史记录仍可查看",
                        checked = settings.collectRequestStats,
                        onCheckedChange = { enabled -> scope.launch {
                            SettingsDataStore.setRequestStatsEnabled(enabled)
                        } },
                    )
                    StatsRow(title = "清除历史统计", enabled = records.isNotEmpty(), onClick = { confirmClear = true })
                }
            }
        }
        item(key = "explanation") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatsText("缓存用量来自服务商响应，未知不计入命中率。保留最近 5000 条。")
                exportStatus?.let { StatsText(it) }
                if (loadError) StatsText("统计读取失败，请稍后重试。")
                if (visible.isEmpty()) ListEmptyState(title = "暂无请求统计")
            }
        }
        if (selected == null) {
            items(groups, key = { it.first }) { (conversationId, summary) ->
                StatsGroup {
                    StatsRow(
                        title = titles[conversationId] ?: conversationId,
                        summary = summary,
                        onClick = { onOpenConversation(conversationId) },
                    )
                }
            }
        } else {
            item(key = "summary") {
                StatsText(titles[selected] ?: selected.orEmpty())
                StatsText(visibleTotals)
            }
            items(modelTotals, key = { "model-${it.first}" }) { (model, summary) ->
                StatsText("$model · $summary")
            }
            items(visible, key = { it.id }) { record ->
                StatsGroup {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatsText("${time(record.startedAt)} · ${record.subagentId} · ${record.model}")
                        StatsText("${record.profile} · 第 ${record.ordinal} 次 · ${status(record.status)} · ${record.purpose}" +
                            (if (record.resumed) " · 续接" else "") +
                            (if (record.contextSummaryCount > 0) " · 历史含压缩摘要" else ""))
                        StatsText("输入 ${number(record.inputTokens)} · 缓存 ${number(record.cachedTokens)} · 输出 ${number(record.outputTokens)}")
                        val inputDelta = record.inputTokens?.let { current -> record.previousInputTokens?.let { current - it } }
                        if (inputDelta != null) StatsText("相对上次输入 ${if (inputDelta >= 0) "+" else ""}$inputDelta token")
                        StatsText("历史：${history(record.historyChange)} · 消息 ${record.previousMessageCount ?: "—"} → ${record.messageCount ?: "未知"}")
                        StatsText("旧消息相同前缀 ${record.commonPrefixMessages ?: "—"} 条 · 请求 ${record.previousRequestBytes ?: "—"} → ${record.requestBytes ?: "未知"} 字节")
                        StatsText("前缀/配置变化：${record.changedFields.joinToString { fieldLabel(it) }.ifBlank { if (record.baselineId == null) "首次，无对照" else "未发现" }}")
                        StatsText("HTTP ${record.httpCode ?: "未知"} · ${record.httpAttempts} 次发送 · ${record.elapsedMs / 1000.0} 秒" + record.errorCode?.let { " · $it" }.orEmpty())
                        if (record.historyChange == "unavailable") StatsText("此协议尚未取得可比较的历史指纹，不能据此归因。")
                        StatsText("${record.host}${record.path}\n请求 ${record.id.take(8)} · 上次 ${record.baselineId?.take(8) ?: "—"}" + record.requestId?.let { "\n服务端请求 ID $it" }.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPage(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit,
    overlay: @Composable () -> Unit, content: LazyListScope.() -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiLazyScaffoldPage(title, onBack, actions = actions, overlay = overlay, content = content)
    else MiuixScaffoldPage(title, onBack, actions = actions, overlay = overlay, content = content)
}

@Composable
private fun StatsGroup(title: String? = null, content: @Composable () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Group(title = title) { content() }
    else {
        title?.let { SmallTitle(it) }
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun StatsRow(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
    else ArrowPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
}

@Composable
private fun StatsText(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiText(text = text, kind = CouiTextKind.ItemSummary)
    else Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
