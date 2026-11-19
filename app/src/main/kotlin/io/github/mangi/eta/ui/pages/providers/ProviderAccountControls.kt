package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.local.codexusage.CodexUsagePanel
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Codex / Grok 账号区：当前账号状态、多账号列表与手动操作。
 *
 * 只展示可判定的登录状态和服务端返回的额度，不展示或复制令牌；
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
    onSelectAccount: (String) -> Unit,
    onReloginAccount: (String) -> Unit,
    onRenameAccount: (String, String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    providerId: String,
    onRebuildIndex: () -> Unit = {},
) {
    val prompt = state.prompt
    val account = state.account
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val brand = if (authMode == ProviderAuthModes.XAI_GROK) "Grok" else "Codex"
    val accounts = state.accounts
    val multiAccount = authMode == ProviderAuthModes.OPENAI_CHATGPT && accounts.isNotEmpty()
    var showAccounts by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    val current = state.selected
    var manageId by remember { mutableStateOf<String?>(null) }
    val managed = accounts.firstOrNull { it.accountId == manageId } ?: current

    if (authMode == ProviderAuthModes.OPENAI_CHATGPT && state.errorCode == "oauth_accounts_unreadable") {
        BasicComponent(title = "恢复旧 Codex 连接", summary = "账号索引损坏，重建前会保全旧索引；其他服务商与聊天保持原状",
            endActions = { TextButton(text = "重建索引", enabled = enabled && !state.isBusy,
                onClick = { showRecovery = true }) })
    }
    if (showRecovery) {
        OverlayDialog(show = true, title = "重建 Codex 账号索引",
            summary = "损坏索引无法安全区分各个 Codex 连接。确认后所有旧 Codex 账号槽会被隔离，所有 Codex 连接都需要重新登录。原索引保全在本机；不会删除聊天、其他服务商或取消订阅。",
            onDismissRequest = { showRecovery = false }) {
            MiuixDialogActions(confirmText = "确认重建所有 Codex 索引", destructive = true,
                onCancel = { showRecovery = false }, onConfirm = { showRecovery = false; onRebuildIndex() })
        }
    }

    val title = current?.name?.takeIf { it.isNotBlank() } ?: account?.email?.let(::maskedAccountEmail)
        ?: account?.displayName ?: stringResource(R.string.provider_i18n_account_title, brand)
    val summary = browserError ?: state.message ?: when (state.phase) {
        ProviderLoginPhase.Loading -> stringResource(R.string.provider_i18n_login_loading)
        ProviderLoginPhase.Saving -> stringResource(R.string.provider_i18n_login_saving)
        ProviderLoginPhase.Disconnecting -> stringResource(R.string.provider_i18n_login_disconnecting)
        ProviderLoginPhase.Authorizing -> stringResource(
            if (prompt == null) R.string.provider_i18n_login_preparing else R.string.provider_i18n_login_browser_prompt,
        )
        ProviderLoginPhase.SyncingModels -> stringResource(R.string.provider_i18n_login_syncing_models)
        ProviderLoginPhase.Idle -> when {
            current?.credentialError != null -> "本机无法读取该账号凭据，可重新登录或从本机移除"
            account?.needsRefresh == true -> "凭据已到刷新时间，下次请求会刷新；有效性尚待服务端确认"
            account == null && multiAccount -> "当前未选择账号，请选择或登录一个账号"
            account == null -> stringResource(
                if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    R.string.provider_i18n_login_chatgpt_hint
                } else {
                    R.string.provider_i18n_login_grok_hint
                },
            )
            account.needsReauthentication -> stringResource(R.string.provider_i18n_login_reauth)
            !account.planEnabled -> stringResource(R.string.provider_i18n_login_plan_disabled)
            authMode == ProviderAuthModes.OPENAI_CHATGPT -> stringResource(R.string.provider_i18n_logged_in_codex)
            else -> stringResource(R.string.provider_i18n_logged_in_grok)
        }
    }
    val openBrowserText = stringResource(R.string.provider_i18n_open_browser)
    val cancelText = stringResource(R.string.action_cancel)
    val loginActionText = if (loggedIn) {
        stringResource(R.string.provider_i18n_reauthorize)
    } else {
        stringResource(R.string.provider_i18n_sign_in_with, brand)
    }
    val manageUsageText = stringResource(R.string.provider_i18n_manage_usage)
    val signOutText = stringResource(R.string.provider_i18n_sign_out)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreference(
                title = title,
                summary = summary,
                enabled = enabled,
                onClick = if (multiAccount && enabled && !state.isBusy) { { showAccounts = true } } else null,
                showChevron = multiAccount && enabled && !state.isBusy,
                trailing = if (multiAccount) {
                    {
                        AccountHeaderActions(enabled && !state.isBusy, current != null, onLogin,
                            onMore = { manageId = current?.accountId; showMore = true })
                    }
                } else null,
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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (prompt != null) {
                            CouiButton(
                                text = openBrowserText,
                                onClick = onOpenBrowser,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.width(IntrinsicSize.Max),
                            )
                        }
                        if (state.phase in setOf(
                                ProviderLoginPhase.Saving,
                                ProviderLoginPhase.Authorizing,
                                ProviderLoginPhase.SyncingModels,
                                ProviderLoginPhase.Disconnecting,
                            )
                        ) {
                            CouiButton(
                                text = cancelText,
                                onClick = onCancel,
                                modifier = Modifier.width(IntrinsicSize.Max),
                            )
                        }
                        if (multiAccount) {
                            CouiButton(
                                text = "切换账号",
                                enabled = false,
                                onClick = { showAccounts = true },
                                modifier = Modifier.width(IntrinsicSize.Max),
                            )
                        }
                    }
                } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT && accounts.isEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CouiButton(
                            text = if (accounts.isEmpty()) "使用 $brand 登录" else "添加账号",
                            enabled = enabled,
                            onClick = onLogin,
                            role = if (accounts.isEmpty()) CouiButtonRole.Primary else CouiButtonRole.Secondary,
                            modifier = Modifier.width(IntrinsicSize.Max),
                        )
                    }
                } else if (authMode != ProviderAuthModes.OPENAI_CHATGPT) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (needsLogin) {
                            CouiButton(
                                text = loginActionText,
                                enabled = enabled,
                                onClick = onLogin,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loggedIn) {
                            CouiButton(
                                text = signOutText,
                                enabled = enabled,
                                onClick = onDisconnect,
                                role = CouiButtonRole.Destructive,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            BasicComponent(
                title = title,
                onClick = { if (multiAccount && enabled && !state.isBusy) showAccounts = true },
                endActions = {
                    if (multiAccount) {
                        AccountHeaderActions(enabled && !state.isBusy, current != null, onLogin,
                            onMore = { manageId = current?.accountId; showMore = true })
                    }
                },
                summary = summary,
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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (prompt != null) {
                            TextButton(text = openBrowserText, onClick = onOpenBrowser, modifier = Modifier.width(IntrinsicSize.Max))
                        }
                        if (state.phase in setOf(
                                ProviderLoginPhase.Saving,
                                ProviderLoginPhase.Authorizing,
                                ProviderLoginPhase.SyncingModels,
                                ProviderLoginPhase.Disconnecting,
                            )
                        ) {
                            TextButton(text = cancelText, onClick = onCancel, modifier = Modifier.width(IntrinsicSize.Max))
                        }
                    }
                    if (multiAccount) {
                        TextButton(text = "切换账号", enabled = false, onClick = { showAccounts = true })
                    }
                } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT && accounts.isEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = if (accounts.isEmpty()) "使用 $brand 登录" else "添加账号",
                            enabled = enabled,
                            onClick = onLogin,
                            modifier = Modifier.width(IntrinsicSize.Max),
                        )
                    }
                } else if (authMode != ProviderAuthModes.OPENAI_CHATGPT) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (needsLogin) {
                            TextButton(
                                text = loginActionText,
                                enabled = enabled,
                                onClick = onLogin,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loggedIn) {
                            TextButton(text = signOutText, enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (multiAccount && providerId.isNotBlank() && current != null) {
        // Dispose any in-flight refresh and remembered state before showing a different account.
        key(providerId, current.accountId) {
            CodexUsagePanel(
                providerId = providerId,
                accountId = current.accountId,
                usable = current.usable,
                canManage = loggedIn,
                enabled = enabled && !state.isBusy,
                onManageUsage = onManageUsage,
                accountName = title,
            )
        }
    } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT && loggedIn) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiPreference(
                    title = "剩余额度",
                    summary = "尚未获取额度",
                    showChevron = false,
                    trailing = { TextButton(text = "管理用量", enabled = enabled && !state.isBusy, onClick = onManageUsage) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                BasicComponent(
                    title = "剩余额度",
                    summary = "尚未获取额度",
                    endActions = { TextButton(text = "管理用量", enabled = enabled && !state.isBusy, onClick = onManageUsage) },
                )
            }
        }
    }

    if (multiAccount) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiNoticeDialog(
                    visible = showAccounts,
                    title = "选择 Codex 账号",
                    message = null,
                    confirmText = stringResource(R.string.action_close),
                    onDismissRequest = { showAccounts = false },
                ) {
                    Column {
                        accounts.forEach { item ->
                            CouiPreference(
                                title = item.name.ifBlank { item.email?.let(::maskedAccountEmail) ?: item.displayName ?: "Codex账号" },
                                summary = when { item.selected -> "当前账号"; item.usable -> "已登录"; else -> "需要重新登录" },
                                enabled = enabled && !state.isBusy,
                                trailing = {
                                    TextButton(
                                        text = "管理",
                                        enabled = enabled && !state.isBusy,
                                        onClick = {
                                            manageId = item.accountId
                                            showAccounts = false
                                            showMore = true
                                        },
                                    )
                                },
                                onClick = {
                                    if (item.usable) onSelectAccount(item.accountId)
                                    else {
                                        manageId = item.accountId
                                        showMore = true
                                    }
                                    showAccounts = false
                                },
                            )
                        }
                    }
                }
                if (managed != null) {
                    CouiNoticeDialog(
                        visible = showMore,
                        title = "账号操作",
                        message = null,
                        confirmText = stringResource(R.string.action_close),
                        onDismissRequest = { showMore = false },
                    ) {
                        CodexAccountRow(
                            account = managed,
                            enabled = enabled && !state.isBusy,
                            busy = state.isBusy && state.activeAccountId == managed.accountId,
                            brand = brand,
                            onSelect = { onSelectAccount(managed.accountId); showMore = false },
                            onRelogin = { onReloginAccount(managed.accountId); showMore = false },
                            onRename = { onRenameAccount(managed.accountId, it); showMore = false },
                            onRemove = { onRemoveAccount(managed.accountId); showMore = false },
                        )
                    }
                }
            }
            AppearanceUiStyle.MIUIX -> {
                top.yukonga.miuix.kmp.overlay.OverlayBottomSheet(
                    show = showAccounts,
                    title = "选择 Codex 账号",
                    onDismissRequest = { showAccounts = false },
                ) {
                    Column {
                        accounts.forEach { item ->
                            BasicComponent(
                                title = item.name.ifBlank { item.email?.let(::maskedAccountEmail) ?: item.displayName ?: "Codex账号" },
                                summary = when { item.selected -> "当前账号"; item.usable -> "已登录"; else -> "需要重新登录" },
                                enabled = enabled && !state.isBusy,
                                endActions = {
                                    TextButton(
                                        text = "管理",
                                        onClick = {
                                            manageId = item.accountId
                                            showAccounts = false
                                            showMore = true
                                        },
                                    )
                                },
                                onClick = {
                                    if (item.usable) onSelectAccount(item.accountId)
                                    else {
                                        manageId = item.accountId
                                        showMore = true
                                    }
                                    showAccounts = false
                                },
                            )
                        }
                    }
                }
                if (managed != null) {
                    top.yukonga.miuix.kmp.overlay.OverlayBottomSheet(
                        show = showMore,
                        title = "账号操作",
                        onDismissRequest = { showMore = false },
                    ) {
                        CodexAccountRow(
                            account = managed,
                            enabled = enabled && !state.isBusy,
                            busy = state.isBusy && state.activeAccountId == managed.accountId,
                            brand = brand,
                            onSelect = { onSelectAccount(managed.accountId); showMore = false },
                            onRelogin = { onReloginAccount(managed.accountId); showMore = false },
                            onRename = { onRenameAccount(managed.accountId, it); showMore = false },
                            onRemove = { onRemoveAccount(managed.accountId); showMore = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountHeaderActions(enabled: Boolean, canManage: Boolean, onAdd: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipBox(text = "添加账号", enabled = enabled) {
            IconButton(onClick = onAdd, enabled = enabled) { Icon(Icons.Rounded.Add, contentDescription = "添加账号") }
        }
        if (canManage) TooltipBox(text = "更多", enabled = enabled) {
            IconButton(onClick = onMore, enabled = enabled) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "更多") }
        }
    }
}
internal fun maskedAccountEmail(email: String): String = email.substringBefore('@').take(1) + "***@" + email.substringAfter('@', "***")

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
    val detail = listOfNotNull(account.email?.takeIf { it != account.name }?.let(::maskedAccountEmail), account.displayName?.takeIf { it != account.email })
        .distinct()
        .joinToString(" · ")
        .ifBlank { null }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(
                        text = account.name.ifBlank { account.email?.let(::maskedAccountEmail) ?: account.displayName ?: "Codex账号" },
                        style = MiuixTheme.textStyles.headline1,
                    )
                    Text(
                        text = listOfNotNull(status, detail).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (account.loggedIn) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy) {
                        CouiButton(text = "处理中…", enabled = false, onClick = {}, modifier = Modifier.weight(1f))
                    } else {
                        if (!account.selected && account.usable) {
                            CouiButton(text = "使用此账号", enabled = enabled, onClick = onSelect, role = CouiButtonRole.Primary, modifier = Modifier.weight(1f))
                        }
                        CouiButton(text = "重命名", enabled = enabled, onClick = { showRename = true }, modifier = Modifier.weight(1f))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CouiButton(text = "重新登录", enabled = enabled, onClick = onRelogin, modifier = Modifier.weight(1f))
                    CouiButton(text = "删除", enabled = enabled, onClick = { showRemove = true }, role = CouiButtonRole.Destructive, modifier = Modifier.weight(1f))
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = account.name.ifBlank { account.email?.let(::maskedAccountEmail) ?: account.displayName ?: "Codex账号" }, style = MiuixTheme.textStyles.headline1)
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
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(account.name) }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiInputDialog(
                    visible = true,
                    title = "账号备注",
                    message = "备注只保存在本机，用于区分账号；不会修改账号本身。",
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = "备注名称",
                    confirmText = "保存",
                    onConfirm = {
                        showRename = false
                        onRename(name)
                    },
                    onDismissRequest = { showRename = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
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
        }
    }

    if (showRemove) {
        val current = account.selected
        val summary = (if (current) {
            "删除「${account.label}」后当前不会自动改用其他账号，需要手动选择或重新登录。"
        } else {
            "将从 $brand 连接中移除「${account.label}」，不影响其他账号与对话记录。"
        }) + " 仅删除本机连接，不取消付费订阅；不会请求远端撤销。"
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = "删除账号",
                    message = summary,
                    confirmText = "删除",
                    destructive = true,
                    onConfirm = {
                        showRemove = false
                        onRemove()
                    },
                    onDismissRequest = { showRemove = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDialog(
                    show = true,
                    title = "删除账号",
                    summary = summary,
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
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    context.getString(R.string.provider_i18n_no_browser)
} catch (_: SecurityException) {
    context.getString(R.string.provider_i18n_cannot_open_browser)
}
