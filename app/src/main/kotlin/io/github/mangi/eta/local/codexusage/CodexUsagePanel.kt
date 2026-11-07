package io.github.mangi.eta.local.codexusage

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val usageAutoRefreshGate = UsageAutoRefreshGate()

@Composable
internal fun CodexUsagePanel(
    providerId: String,
    accountId: String,
    usable: Boolean,
    canManage: Boolean,
    enabled: Boolean,
    onManageUsage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember(providerId, accountId) { mutableStateOf<CodexUsage?>(null) }
    var error by remember(providerId, accountId) { mutableStateOf<String?>(null) }
    var loading by remember(providerId, accountId) { mutableStateOf(false) }
    var cacheLoaded by remember(providerId, accountId) { mutableStateOf(false) }
    var nowMillis by remember(providerId, accountId) { mutableLongStateOf(System.currentTimeMillis()) }
    suspend fun fetch() {
        if (loading || !usable) return
        loading = true
        usageAutoRefreshGate.recordAttempt(providerId, accountId, SystemClock.elapsedRealtime())
        val controller = AgentRunController()
        try {
            snapshot = coroutineScope {
                val work = async(Dispatchers.IO) { CodexUsageRepository.refresh(context, providerId, accountId, controller) }
                try { work.await() } catch (cancelled: CancellationException) { controller.cancel(); throw cancelled }
            }
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = if (failure is ProviderAuthException || failure is AgentModelFailure && failure.code.startsWith("OAUTH")) {
                "账号授权失效，请重新登录；未切换账号"
            } else if (snapshot != null) {
                "额度刷新失败；保留上次数据，不代表剩余为零"
            } else {
                "额度刷新失败；尚无可用数据"
            }
        } finally {
            controller.cancel()
            loading = false
        }
    }
    LaunchedEffect(providerId, accountId, usable) {
        cacheLoaded = false
        snapshot = withContext(Dispatchers.IO) { CodexUsageRepository.cached(context, providerId, accountId) }
        cacheLoaded = true
        if (usable && (snapshot == null || System.currentTimeMillis() - snapshot!!.updatedAt > 60000) &&
            usageAutoRefreshGate.allows(providerId, accountId, SystemClock.elapsedRealtime())
        ) fetch()
    }
    val resetTimes = snapshot?.windows?.mapNotNull { it.resetAt }.orEmpty()
    LaunchedEffect(lifecycle, resetTimes) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                val next = resetTimes.mapNotNull { nextResetCountdownUpdateMillis(it, nowMillis) }.minOrNull()
                    ?: break
                delay(next)
            }
        }
    }
    BasicComponent(
        title = "剩余额度",
        endActions = {
            if (canManage) TextButton(text = "管理用量", enabled = enabled, onClick = onManageUsage)
        },
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        snapshot?.let { saved ->
            if (saved.windows.isEmpty()) {
                UsageNote("服务端未提供可展示的额度窗口")
            } else {
                saved.windows.forEach { window -> UsageWindowRow(window, nowMillis / 1000L) }
            }
            UsageNote("上次获取：" + DateFormat.getDateTimeInstance().format(Date(saved.updatedAt)))
        }
        if (loading) UsageNote(if (snapshot == null) "正在读取真实额度…" else "正在刷新，显示上次获取的数据")
        if (!usable) UsageNote("需重新登录后读取额度")
        error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
        if (snapshot == null && !loading && error == null && usable) {
            UsageNote(if (cacheLoaded) "尚未获取额度" else "正在读取本地额度…")
        }
        TextButton(
            text = if (loading) "刷新中…" else "刷新额度",
            enabled = enabled && usable && cacheLoaded && !loading,
            onClick = { scope.launch { fetch() } },
        )
    }
}

@Composable
private fun UsageWindowRow(window: UsageWindow, nowSeconds: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(resetCountdownText(window.resetAt, nowSeconds), style = MiuixTheme.textStyles.body2)
                UsageNote(windowName(window))
            }
            Text(
                text = "剩余 " + (window.remaining?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "未知"),
                style = MiuixTheme.textStyles.body2,
            )
        }
        val remaining = window.remaining
        if (remaining == null) {
            // Unknown is a neutral track, not an indeterminate animation or a zero-percent value.
            Box(
                Modifier.fillMaxWidth()
                    .height(ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight)
                    .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = (remaining / 100.0).toFloat(),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                ),
            )
        }
    }
}

@Composable
private fun UsageNote(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

internal fun windowName(window: UsageWindow): String {
    val duration = window.seconds?.let { seconds -> when {
        seconds % 3600 == 0L -> "${seconds / 3600}小时"
        seconds % 60 == 0L -> "${seconds / 60}分钟"
        else -> "${seconds}秒"
    } } ?: "未知窗口"
    return "${window.name} · $duration"
}
