package io.github.mangi.eta.local.imagegen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ImageGenSettingsScreen(
    onBack: () -> Unit,
    readAccounts: (String, String) -> List<CodexAccountView> = ProviderCredentialRepository::accounts,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(runCatching { ImageGenConfigStore.profiles(context) }.getOrElse {
        error = "生图配置无法读取，原配置保留"; ImageGenProfiles()
    }) }
    var draft by remember { mutableStateOf(state.profiles.firstOrNull { it.profileId == state.selectedId } ?: ImageGenConfig()) }
    // Keep secrets in ephemeral UI memory only: never rememberSaveable or profile JSON.
    var keyDraft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<CodexAccountView>>(emptyList()) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<ProviderLoginPrompt?>(null) }
    var removeAccount by remember { mutableStateOf<String?>(null) }
    var removeProfile by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<AgentRunController?>(null) }
    DisposableEffect(Unit) { onDispose { controller?.cancel() } }
    LaunchedEffect(draft.credentialPoolId, draft.source, saving) {
        val loaded = withContext(Dispatchers.IO) { loadImageGenAccounts(draft, readAccounts) }
        accounts = loaded.accounts; accountError = loaded.error
    }
    fun operation(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action(); state = withContext(Dispatchers.IO) { ImageGenConfigStore.profiles(context) }; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? io.github.mangi.eta.agent.auth.ProviderAuthException)?.let {
                "${it.stage?.let { phase -> "[$phase] " }.orEmpty()}${it.message}"
            } ?: "操作失败，原配置保留，请检查输入后重试" }
            finally { saving = false; prompt = null; controller = null }
        }
    }
    fun login(accountId: String? = null) {
        operation {
            val run = AgentRunController(); controller = run
            withContext(Dispatchers.IO) {
                ProviderCredentialRepository.login(draft.credentialPoolId, ProviderAuthModes.OPENAI_CHATGPT, run,
                    onPrompt = { next -> scope.launch { prompt = next } }, accountId = accountId)
            }
        }
    }
    MiuixScaffoldPage(title = "生图配置", onBack = onBack) {
        item(key = "profiles") {
            SmallTitle("独立生图连接")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                OverlayDropdownPreference(title = "当前连接", enabled = !saving,
                    items = listOf("未启用") + state.profiles.map { it.name.ifBlank { "未命名连接" } },
                    selectedIndex = state.profiles.indexOfFirst { it.profileId == state.selectedId } + 1,
                    onSelectedIndexChange = { index ->
                        val selected = state.profiles.getOrNull(index - 1)
                        operation { withContext(Dispatchers.IO) { ImageGenConfigStore.select(context, selected?.profileId) }
                            draft = selected ?: ImageGenConfig(); keyDraft = "" }
                    })
                TextButton(text = "新增生图连接", enabled = !saving, onClick = {
                    draft = ImageGenConfig(profileId = UUID.randomUUID().toString(), source = ImageGenSource.OPENAI_API,
                        name = "生图连接 ${state.profiles.size + 1}"); keyDraft = ""
                })
                if (state.profiles.isEmpty()) Text("尚未创建生图连接。可独立填写 API，或登录 Codex；无需创建语言模型服务商。",
                    modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                if (runCatching { ImageGenConfigStore.legacy(context) }.getOrNull() != null) {
                    Text("检测到旧生图配置，原记录已保留。请手动确认地址、Key 和账号后创建独立连接；不会继承旧付费来源。",
                        modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote2)
                }
            }
        }
        item(key = "editor") {
            if (draft.profileId.isNotBlank()) {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) }, label = "连接名称",
                            enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val sources = listOf(ImageGenSource.OPENAI_API, ImageGenSource.CODEX_SUBSCRIPTION)
                        OverlayDropdownPreference(title = "生图来源", items = listOf("独立 Images API", "Codex 订阅"), enabled = !saving,
                            selectedIndex = sources.indexOf(draft.source).coerceAtLeast(0), onSelectedIndexChange = {
                                draft = draft.copy(source = sources[it], accountId = "", model = "gpt-image-2")
                            })
                        if (draft.source == ImageGenSource.OPENAI_API) {
                            TextField(value = draft.endpoint, onValueChange = { draft = draft.copy(endpoint = it) },
                                label = "完整 HTTPS Images endpoint", enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextField(value = keyDraft, onValueChange = { keyDraft = it },
                                label = if (draft.keyConfigured) "替换 API Key（留空保留）" else "独立 API Key", enabled = !saving,
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextField(value = draft.model, onValueChange = { draft = draft.copy(model = it) }, label = "生图模型",
                                enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else {
                            OverlayDropdownPreference(title = "固定 Codex 账号", enabled = !saving,
                                items = listOf("请选择固定账号") + accounts.map { it.label + if (it.usable) "" else " · 需重新登录" },
                                selectedIndex = accounts.indexOfFirst { it.accountId == draft.accountId } + 1,
                                onSelectedIndexChange = { draft = draft.copy(accountId = accounts.getOrNull(it - 1)?.accountId.orEmpty()) })
                            TextButton(text = "添加独立 Codex 账号", enabled = !saving, onClick = { login() })
                            accountError?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote2) }
                            if (prompt != null) TextButton(text = "打开授权浏览器", onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt!!.authorizationUrl))) }
                                catch (_: Exception) { error = "[browser_launch] 无法打开授权浏览器"; controller?.cancel() }
                            })
                            if (saving && controller != null) TextButton(text = "取消登录", onClick = { controller?.cancel() })
                            accounts.forEach { item ->
                                Text(item.label + if (item.usable) "" else " · 需要重新登录")
                                TextButton(text = "重新登录", enabled = !saving, onClick = { login(item.accountId) })
                                TextButton(text = "从本机移除账号", enabled = !saving, onClick = { removeAccount = item.accountId })
                            }
                        }
                        OverlayDropdownPreference(title = "尺寸", items = ImageGenConfig.SIZES, enabled = !saving,
                            selectedIndex = ImageGenConfig.SIZES.indexOf(draft.size).coerceAtLeast(0),
                            onSelectedIndexChange = { draft = draft.copy(size = ImageGenConfig.SIZES[it]) })
                        OverlayDropdownPreference(title = "质量", items = ImageGenConfig.QUALITIES, enabled = !saving,
                            selectedIndex = ImageGenConfig.QUALITIES.indexOf(draft.quality).coerceAtLeast(0),
                            onSelectedIndexChange = { draft = draft.copy(quality = ImageGenConfig.QUALITIES[it]) })
                        OverlayDropdownPreference(title = "背景", items = ImageGenConfig.BACKGROUNDS, enabled = !saving,
                            selectedIndex = ImageGenConfig.BACKGROUNDS.indexOf(draft.background).coerceAtLeast(0),
                            onSelectedIndexChange = { draft = draft.copy(background = ImageGenConfig.BACKGROUNDS[it]) })
                        TextButton(text = "保存并启用连接", enabled = !saving, onClick = {
                            val snapshot = draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim(),
                                keyConfigured = draft.keyConfigured || keyDraft.isNotBlank(),
                                credentialId = if (keyDraft.isNotBlank()) UUID.randomUUID().toString() else draft.credentialId,
                                retiredCredentialIds = if (keyDraft.isNotBlank() && draft.keyConfigured)
                                    (draft.retiredCredentialIds + draft.keySlotId).distinct() else draft.retiredCredentialIds)
                            val key = keyDraft
                            val problem = snapshot.validate()
                            if (problem != null) error = problem else operation {
                                withContext(Dispatchers.IO) {
                                    val credentials = ImageGenCredentials(context)
                                    if (key.isNotBlank()) credentials.write(snapshot.keySlotId, key)
                                    try { ImageGenConfigStore.write(context, snapshot) }
                                    catch (failure: Exception) {
                                        if (key.isNotBlank()) credentials.delete(snapshot.keySlotId)
                                        throw failure
                                    }
                                }
                                draft = snapshot; keyDraft = ""
                            }
                        })
                        if (state.profiles.any { it.profileId == draft.profileId }) TextButton(text = "删除此生图连接",
                            enabled = !saving, onClick = { removeProfile = true })
                    }
                }
            }
        }
        item(key = "status") {
            Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt != null) TextButton(text = "打开授权浏览器", onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt!!.authorizationUrl))) }
                    catch (_: Exception) { error = "[browser_launch] 无法打开授权浏览器"; controller?.cancel() }
                })
                if (saving && controller != null) TextButton(text = "取消登录", onClick = { controller?.cancel() })
                error?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote2) }
                accountError?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote2) }
                Text("编辑后点击保存，快速编辑使用同一份草稿。生图固定使用当前独立连接和账号，不随聊天切换；请求只执行一次，失败不换来源。",
                    style = MiuixTheme.textStyles.footnote2)
            }
        }
    }
    if (removeAccount != null || removeProfile) {
        OverlayDialog(show = true, title = "确认从本机移除", summary = "只删除此独立生图连接或账号；不取消付费订阅，不影响聊天与其他连接，远端撤销未确认。",
            onDismissRequest = { removeAccount = null; removeProfile = false }) {
            TextButton(text = "取消", onClick = { removeAccount = null; removeProfile = false })
            TextButton(text = "确认移除", onClick = {
                val accountId = removeAccount; val profile = draft
                removeAccount = null; removeProfile = false
                operation {
                    withContext(Dispatchers.IO) {
                        if (accountId != null) ProviderCredentialRepository.removeAccount(profile.credentialPoolId,
                            ProviderAuthModes.OPENAI_CHATGPT, accountId, AgentRunController())
                        else {
                            val credentials = ImageGenCredentials(context)
                            (profile.retiredCredentialIds + profile.keySlotId).distinct().forEach(credentials::delete)
                            ProviderCredentialRepository.forget(profile.credentialPoolId)
                            ImageGenConfigStore.remove(context, profile.profileId)
                        }
                    }
                    draft = if (accountId != null) draft.copy(accountId = draft.accountId.takeIf { it != accountId }.orEmpty()) else ImageGenConfig()
                }
            })
        }
    }
}
