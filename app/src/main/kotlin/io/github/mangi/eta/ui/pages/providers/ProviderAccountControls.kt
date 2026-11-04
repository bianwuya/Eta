package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Codex / Grok 账号区：当前账号状态、多账号列表与手动操作。
 *
 * 只展示可判定的登录状态，不展示或复制令牌、额度或冷却信息；
 * 切换账号只影响之后发起的请求，已在途的请求保持原绑定。
 */
@Composable
internal fun ProviderAccountControls(
    state: ProviderLoginState,
    authMode: String,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onSelectAccount: (String) -> Unit = {},
    onReloginAccount: (String) -> Unit = {},
    onRenameAccount: (String, String) -> Unit = { _, _ -> },
    onRemoveAccount: (String) -> Unit = {},
) {
    val prompt = state.prompt
    val account = state.account
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val brand = if (authMode == ProviderAuthModes.XAI_GROK) "Grok" else "Codex"
    val accounts = state.accounts
    val multiAccount = authMode == ProviderAuthModes.OPENAI_CHATGPT && accounts.isNotEmpty()

    BasicComponent(
        title = account?.email ?: account?.displayName ?: "$brand 账号",
        summary = browserError ?: state.message ?: when (state.phase) {
            ProviderLoginPhase.Loading -> "正在读取登录状态…"
            ProviderLoginPhase.Saving -> "正在保存配置…"
            ProviderLoginPhase.Disconnecting -> "正在处理账号…"
            ProviderLoginPhase.Authorizing -> if (prompt == null) "正在准备授权…" else "请在浏览器中完成授权"
            ProviderLoginPhase.SyncingModels -> "登录成功，正在拉取最新模型…"
            ProviderLoginPhase.Idle -> when {
                account == null && multiAccount -> "当前未选择账号，请选择或登录一个账号"
                account == null -> if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    "使用 ChatGPT 账号登录 Codex"
                } else {
                    "登录后使用 Grok 订阅额度"
                }
                account.needsReauthentication -> "请重新登录"
                !account.planEnabled -> "账号尚未授予模型调用权限"
                authMode == ProviderAuthModes.OPENAI_CHATGPT -> "已登录 Codex"
                else -> "已登录 Grok"
            }
        },
    )
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        prompt?.userCode?.let { userCode ->
            SelectionContainer {
                Text(
                    text = userCode,
                    style = MiuixTheme.textStyles.headline1,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        if (state.isBusy) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prompt != null) {
                    TextButton(text = "打开浏览器", onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                }
                if (state.phase in setOf(
                        ProviderLoginPhase.Saving,
                        ProviderLoginPhase.Authorizing,
                        ProviderLoginPhase.SyncingModels,
                        ProviderLoginPhase.Disconnecting,
                    )
                ) {
                    TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    TextButton(
                        text = if (accounts.isEmpty()) "使用 $brand 登录" else "添加账号",
                        enabled = enabled,
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                    if (loggedIn) {
                        TextButton(text = "管理用量", enabled = enabled, onClick = onManageUsage, modifier = Modifier.weight(1f))
                    }
                } else if (needsLogin) {
                    TextButton(
                        text = if (loggedIn) "重新授权" else "使用 $brand 登录",
                        enabled = enabled,
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (loggedIn && authMode != ProviderAuthModes.OPENAI_CHATGPT) {
                    TextButton(text = "退出登录", enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (multiAccount) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            accounts.forEach { item ->
                CodexAccountRow(
                    account = item,
                    enabled = enabled && !state.isBusy,
                    busy = state.isBusy && state.activeAccountId == item.accountId,
                    brand = brand,
                    onSelect = { onSelectAccount(item.accountId) },
                    onRelogin = { onReloginAccount(item.accountId) },
                    onRename = { name -> onRenameAccount(item.accountId, name) },
                    onRemove = { onRemoveAccount(item.accountId) },
                )
            }
        }
    }
}

@Composable
private fun CodexAccountRow(
    account: CodexAccountView,
    enabled: Boolean,
    busy: Boolean,
    brand: String,
    onSelect: () -> Unit,
    onRelogin: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val status = when {
        account.selected && account.loggedIn -> "当前使用"
        account.loggedIn -> "已登录"
        else -> "需要重新登录"
    }
    val detail = listOfNotNull(account.email?.takeIf { it != account.name }, account.displayName?.takeIf { it != account.email })
        .distinct()
        .joinToString(" · ")
        .ifBlank { null }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = account.label, style = MiuixTheme.textStyles.headline1)
                    Text(
                        text = listOfNotNull(status, detail).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (account.loggedIn) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (busy) {
                    TextButton(text = "处理中…", enabled = false, onClick = {}, modifier = Modifier.weight(1f))
                } else {
                    if (!account.selected && account.usable) {
                        TextButton(text = "使用此账号", enabled = enabled, onClick = onSelect, modifier = Modifier.weight(1f))
                    }
                    TextButton(text = "重命名", enabled = enabled, onClick = { showRename = true }, modifier = Modifier.weight(1f))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(text = "重新登录", enabled = enabled, onClick = onRelogin, modifier = Modifier.weight(1f))
                TextButton(text = "删除", enabled = enabled, onClick = { showRemove = true }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(account.name) }
        OverlayDialog(
            show = true,
            title = "账号备注",
            summary = "备注只保存在本机，用于区分账号；不会修改账号本身。",
            onDismissRequest = { showRename = false },
        ) {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = "备注名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "留空时显示邮箱或序号",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                MiuixDialogActions(
                    confirmText = "保存",
                    onCancel = { showRename = false },
                    onConfirm = {
                        showRename = false
                        onRename(name)
                    },
                )
            }
        }
    }

    if (showRemove) {
        val current = account.selected
        OverlayDialog(
            show = true,
            title = "删除账号",
            summary = if (current) {
                "删除「${account.label}」后当前不会自动改用其他账号，需要手动选择或重新登录。"
            } else {
                "将从 $brand 连接中移除「${account.label}」，不影响其他账号与对话记录。"
            },
            onDismissRequest = { showRemove = false },
        ) {
            MiuixDialogActions(
                confirmText = "删除",
                destructive = true,
                onCancel = { showRemove = false },
                onConfirm = {
                    showRemove = false
                    onRemove()
                },
            )
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "未找到可用浏览器"
} catch (_: SecurityException) {
    "无法打开浏览器，请检查系统设置"
}
