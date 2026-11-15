package io.github.mangi.eta.local.codexusage

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val usageAutoRefreshGate = UsageAutoRefreshGate()
// A confirmed redemption belongs to its account, including after leaving this screen.
private val redemptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun resetAuthorizationFailed(failure: Throwable): Boolean = failure is ProviderAuthException ||
    failure is AgentModelFailure && (failure.code.startsWith("OAUTH") ||
        failure.code in setOf("HTTP_401", "HTTP_403", "PROVIDER_AUTH_FAILED"))

private fun resetWindowsNotice(names: List<String>): String = if (names.isEmpty()) "" else
    "；服务端确认窗口：" + names.joinToString { it.take(80) }

@Composable
internal fun CodexUsagePanel(
    providerId: String,
    accountId: String,
    usable: Boolean,
    canManage: Boolean,
    enabled: Boolean,
    onManageUsage: () -> Unit,
    accountName: String = accountId,
) = key(providerId, accountId) {
    CodexAccountUsagePanel(providerId, accountId, accountName, usable, canManage, enabled, onManageUsage)
}

@Composable
private fun CodexAccountUsagePanel(
    providerId: String,
    accountId: String,
    accountName: String,
    usable: Boolean,
    canManage: Boolean,
    enabled: Boolean,
    onManageUsage: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember { mutableStateOf<CodexUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(false) }
    var cacheLoaded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var pendingReadable by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val inFlightKeys by CodexResetRepository.inFlightKeys.collectAsState()
    val resetting = submitting || CodexResetRepository.key(providerId, accountId) in inFlightKeys

    suspend fun restorePending() {
        try {
            pending = withContext(Dispatchers.IO) { CodexResetRepository.hasPendingConfirmation(context, providerId, accountId) }
            pendingReadable = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pendingReadable = false
            error = "无法读取上次重置记录，重置操作暂不可用"
        }
    }

    suspend fun fetch() {
        if (loading || !usable || resetting || CodexResetRepository.isInFlight(providerId, accountId)) return
        loading = true
        fresh = false
        usageAutoRefreshGate.recordAttempt(providerId, accountId, SystemClock.elapsedRealtime())
        val controller = AgentRunController()
        try {
            snapshot = coroutineScope {
                val work = async(Dispatchers.IO) { CodexUsageRepository.refresh(context, providerId, accountId, controller) }
                try { work.await() } catch (cancelled: CancellationException) { controller.cancel(); throw cancelled }
            }
            fresh = true
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = if (resetAuthorizationFailed(failure)) {
                "账号授权失效，请重新登录"
            } else if (snapshot != null) {
                "额度刷新失败，显示上次数据"
            } else {
                "额度刷新失败，尚无可用数据"
            }
        } finally {
            controller.cancel()
            loading = false
        }
    }

    LaunchedEffect(providerId, accountId, usable) {
        snapshot = withContext(Dispatchers.IO) { CodexUsageRepository.cached(context, providerId, accountId) }
        restorePending()
        cacheLoaded = true
        if (usable && usageAutoRefreshGate.allows(providerId, accountId, SystemClock.elapsedRealtime())) fetch()
    }
    LaunchedEffect(resetting) {
        if (!resetting && cacheLoaded) {
            restorePending()
            val cached = withContext(Dispatchers.IO) { CodexUsageRepository.cached(context, providerId, accountId) }
            if (cached != null && (snapshot == null || cached.updatedAt > snapshot!!.updatedAt)) snapshot = cached
        }
    }
    LaunchedEffect(enabled, usable) { if (!enabled || !usable) confirming = false }
    val resetTimes = snapshot?.windows?.mapNotNull { it.resetAt }.orEmpty()
    LaunchedEffect(lifecycle, resetTimes, expanded) {
        if (!expanded) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                val next = resetTimes.mapNotNull { nextResetCountdownUpdateMillis(it, nowMillis) }.minOrNull() ?: break
                delay(next)
            }
        }
    }

    val canReset = enabled && usable && cacheLoaded && pendingReadable && !loading && !resetting &&
        (pending || fresh && (snapshot?.resetCreditsAvailable ?: 0) > 0 && snapshot?.resetCreditsApplicable != 0)
    fun redeem() {
        if (!canReset || submitting || CodexResetRepository.isInFlight(providerId, accountId)) return
        confirming = false
        submitting = true
        error = null
        notice = null
        redemptionScope.launch {
            val controller = AgentRunController()
            val result = try {
                CodexResetRepository.consume(context, providerId, accountId, controller)
            } catch (failure: Exception) {
                CodexResetResult.Failed(failure, unconfirmed = true)
            } finally { controller.cancel() }
            withContext(Dispatchers.Main.immediate) {
                submitting = false
                restorePending()
                when (result) {
                    is CodexResetResult.Success -> {
                        snapshot = result.usage
                        fresh = true
                        notice = (if (result.code == ResetOutcomeCode.ALREADY_REDEEMED) "本次重置已确认，额度已更新" else "重置成功，额度已更新") + resetWindowsNotice(result.resetWindowNames)
                    }
                    is CodexResetResult.SuccessPendingRefresh -> {
                        fresh = false
                        error = "重置已成功，但额度刷新失败，请刷新查看最新数据" + resetWindowsNotice(result.resetWindowNames)
                    }
                    is CodexResetResult.Rejected -> {
                        fresh = false
                        notice = when (result.code) {
                            ResetOutcomeCode.NO_CREDIT -> "没有可用重置机会，请刷新额度"
                            ResetOutcomeCode.NOTHING_TO_RESET -> "当前没有可重置的额度窗口"
                            else -> "服务端未执行重置，请刷新额度"
                        }
                    }
                    is CodexResetResult.Failed -> {
                        fresh = false
                        error = when {
                            resetAuthorizationFailed(result.error) -> "账号授权失效，请重新登录" + if (result.unconfirmed) "；上次重置结果仍待确认" else ""
                            result.unconfirmed -> "重置结果待确认，可核对本次操作"
                            else -> "重置失败，请刷新后重试"
                        }
                    }
                }
            }
        }
    }

    CodexUsageContent(
        snapshot = snapshot, expanded = expanded, nowSeconds = nowMillis / 1000L,
        loading = loading, resetting = resetting, pending = pending, fresh = fresh,
        usable = usable, cacheLoaded = cacheLoaded, canReset = canReset,
        canRefresh = enabled && usable && cacheLoaded && !loading && !resetting,
        error = error, notice = notice, canManage = canManage && enabled,
        onToggleDetails = { expanded = !expanded }, onRefresh = { scope.launch { fetch() } },
        onReset = { confirming = true }, onManageUsage = onManageUsage,
    )
    if (confirming) {
        val message = if (pending) {
            "目标账号：$accountName\n核对上次重置结果；尚未处理时继续本次重置，不会重复消耗。"
        } else {
            "目标账号：$accountName\n将消耗 1 次重置机会，当前可用 ${snapshot?.resetCreditsAvailable ?: 0} 次。"
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiConfirmDialog(visible = true, title = if (pending) "核对本次重置" else "重置额度", message = message,
                confirmText = if (pending) "核对本次操作" else "确认重置", confirmEnabled = canReset,
                onConfirm = ::redeem, onDismissRequest = { confirming = false })
        } else {
            WindowDialog(show = true, title = if (pending) "核对本次重置" else "重置额度",
                onDismissRequest = { confirming = false }) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(message, style = MiuixTheme.textStyles.body2)
                    MiuixDialogActions(confirmText = if (pending) "核对本次操作" else "确认重置",
                        confirmEnabled = canReset, onCancel = { confirming = false }, onConfirm = ::redeem)
                }
            }
        }
    }
}

@Composable
internal fun CodexUsageContent(
    snapshot: CodexUsage?, expanded: Boolean, nowSeconds: Long,
    loading: Boolean, resetting: Boolean, pending: Boolean, fresh: Boolean,
    usable: Boolean, cacheLoaded: Boolean, canReset: Boolean, canRefresh: Boolean,
    error: String?, notice: String?, canManage: Boolean,
    onToggleDetails: () -> Unit, onRefresh: () -> Unit, onReset: () -> Unit, onManageUsage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = if (expanded) "收起额度详情" else "展开额度详情",
                onClick = onToggleDetails).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("剩余额度", style = MiuixTheme.textStyles.body2)
                    Text(usageSummary(snapshot), style = MiuixTheme.textStyles.body1)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起额度详情" else "展开额度详情")
            }
            TooltipBox(text = "刷新额度", enabled = canRefresh) {
                IconButton(onClick = onRefresh, enabled = canRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新额度")
                }
            }
        }
        if (loading) UsageNote(if (snapshot == null) "正在读取真实额度…" else "正在刷新，显示上次数据")
        if (!usable) UsageNote("需重新登录后读取额度")
        if (snapshot == null && !loading && error == null && usable) UsageNote(if (cacheLoaded) "尚未获取额度" else "正在读取本地额度…")
        if (resetting) UsageNote("正在重置额度…")
        if (pending && !resetting) UsageNote("上次重置结果待确认")
        notice?.let { UsageNote(it) }
        error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
        if (expanded) {
            snapshot?.let { saved ->
                if (saved.windows.isEmpty()) UsageNote("服务端未提供可展示的额度窗口")
                saved.windows.forEach { window -> UsageWindowRow(window, nowSeconds) }
                UsageNote("上次获取：" + DateFormat.getDateTimeInstance().format(Date(saved.updatedAt)))
            }
            UsageNote("可用重置机会：" + (snapshot?.resetCreditsAvailable?.let { "$it 次" } ?: "未知"))
            snapshot?.resetCreditsApplicable?.let { UsageNote("当前适用：$it 次") }
            if (snapshot?.resetCreditsApplicable == 0 && !pending) UsageNote("当前没有可使用重置机会的额度窗口")
            snapshot?.resetCredits?.forEach { UsageNote("重置机会有效期：${it.expiresAt}") }
            snapshot?.resetCreditsError?.let { UsageNote(it) }
            if (!fresh && !loading && !pending && usable) UsageNote("刷新额度后可使用重置机会")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiButton(text = if (pending) "核对本次重置" else "重置额度", onClick = onReset, enabled = canReset,
                        minHeight = 36.dp, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp))
                } else {
                    TextButton(text = if (pending) "核对本次重置" else "重置额度", onClick = onReset, enabled = canReset,
                        minHeight = 36.dp, minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        textStyle = MiuixTheme.textStyles.body2)
                }
                if (canManage) TooltipBox(text = "管理用量") {
                    IconButton(onClick = onManageUsage) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = "管理用量")
                    }
                }
            }
        }
    }
}

internal fun usageSummary(snapshot: CodexUsage?): String {
    if (snapshot == null || snapshot.windows.isEmpty()) return "未知"
    val window = snapshot.windows.filter { it.remaining != null }.minByOrNull { it.remaining!! } ?: return "未知"
    return String.format(Locale.ROOT, "%.1f%%", window.remaining) + " · " + windowName(window)
}

@Composable
private fun UsageWindowRow(window: UsageWindow, nowSeconds: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("剩余 " + (window.remaining?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "未知"), style = MiuixTheme.textStyles.body2)
        UsageNote(windowName(window) + " · " + resetCountdownText(window.resetAt, nowSeconds))
        val remaining = window.remaining
        if (remaining == null) {
            Box(Modifier.fillMaxWidth().height(ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight)
                .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = (remaining / 100.0).toFloat(),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
    }
}

@Composable
private fun UsageNote(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

internal fun windowName(window: UsageWindow): String {
    val duration = window.seconds?.let { seconds -> when {
        seconds % 86400 == 0L -> "${seconds / 86400}天"
        seconds % 3600 == 0L -> "${seconds / 3600}小时"
        seconds % 60 == 0L -> "${seconds / 60}分钟"
        else -> "${seconds}秒"
    } } ?: "未知窗口"
    return "${window.name} · $duration"
}
