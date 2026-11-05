package io.github.mangi.eta.local.codexusage

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CodexUsagePanel(providerId: String, accountId: String, usable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember(providerId, accountId) { mutableStateOf<CodexUsage?>(null) }
    var error by remember(providerId, accountId) { mutableStateOf<String?>(null) }
    var loading by remember(providerId, accountId) { mutableStateOf(false) }
    var expanded by remember(providerId, accountId) { mutableStateOf(false) }
    suspend fun fetch() {
        if (loading || !usable) return
        loading = true
        val controller = AgentRunController()
        try {
            snapshot = coroutineScope {
                val work = async(Dispatchers.IO) { CodexUsageRepository.refresh(context, providerId, accountId, controller) }
                try { work.await() } catch (cancelled: CancellationException) { controller.cancel(); throw cancelled }
            }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = if (failure is ProviderAuthException || failure is AgentModelFailure && failure.code.startsWith("OAUTH")) {
                "账号授权失效，请重新登录；未切换账号"
            } else "额度刷新失败；保留上次数据，不代表剩余为零"
        } finally { controller.cancel(); loading = false }
    }
    LaunchedEffect(providerId, accountId, usable) {
        snapshot = kotlinx.coroutines.withContext(Dispatchers.IO) { CodexUsageRepository.cached(context, providerId, accountId) }
        if (usable && (snapshot == null || System.currentTimeMillis() - snapshot!!.updatedAt > 60000)) fetch()
    }
    val summary = when {
        !usable -> "需重新登录后读取额度"
        snapshot == null -> if (loading) "正在读取真实额度…" else error ?: "尚未获取额度"
        snapshot!!.windows.isEmpty() -> "服务端未提供可展示的额度窗口"
        else -> snapshot!!.windows.take(2).joinToString(" · ") { window ->
            windowName(window) + "剩余" + (window.remaining?.let { String.format(java.util.Locale.ROOT, "%.0f%%", it) } ?: "未知")
        }
    }
    BasicComponent(title = "剩余额度", summary = summary, onClick = { expanded = !expanded })
    if (expanded) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot?.let { saved ->
            saved.windows.forEach { window ->
                val remaining = window.remaining?.let { String.format(java.util.Locale.ROOT, "%.1f%%", it) } ?: "未知"
                val reset = window.resetAt?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it * 1000)) }
                Text("${windowName(window)}剩余 $remaining" + (reset?.let { " · 重置 $it" } ?: ""), style = MiuixTheme.textStyles.footnote2)
            }
            Text("上次获取：" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.updatedAt)),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
        TextButton(text = if (loading) "刷新中…" else "刷新额度", enabled = usable && !loading,
            onClick = { scope.launch { fetch() } })
    }
}

internal fun windowName(window: UsageWindow): String {
    val duration = window.seconds?.let { seconds -> when {
        seconds % 3600 == 0L -> "${seconds / 3600}小时"
        seconds % 60 == 0L -> "${seconds / 60}分钟"
        else -> "${seconds}秒"
    } } ?: "未知窗口"
    return "${window.name} $duration "
}
