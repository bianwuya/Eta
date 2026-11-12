package io.github.mangi.eta.local.imagegen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.window.WindowDialog

@Serializable
private sealed interface ImageGenRoute : NavKey {
    @Serializable data object Connection : ImageGenRoute
    @Serializable data object Models : ImageGenRoute
}

@Composable
internal fun ImageGenSettingsScreen(
    onBack: () -> Unit,
    readAccounts: (String, String) -> List<CodexAccountView> = ProviderCredentialRepository::accounts,
    fetchModels: suspend (String, String) -> Result<List<Model>> = { url, key -> ImageGenModelCatalog.fetch(url, key) },
    credentialsFactory: (android.content.Context) -> ImageGenCredentials = ::ImageGenCredentials,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = rememberNavBackStack<ImageGenRoute>(ImageGenRoute.Connection)
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(runCatching { ImageGenConfigStore.profiles(context) }.getOrElse {
        error = "生图配置无法读取，原配置保留"; ImageGenProfiles()
    }) }
    var draft by remember { mutableStateOf(state.profiles.firstOrNull { it.profileId == state.selectedId } ?: ImageGenConfig()) }
    // Secrets live only in the current composition, never in saved state or profile JSON.
    var keyDraft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<CodexAccountView>>(emptyList()) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<ProviderLoginPrompt?>(null) }
    var removeAccount by remember { mutableStateOf<String?>(null) }
    var removeProfile by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var overrideDraft by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf<AgentRunController?>(null) }
    var catalogJob by remember { mutableStateOf<Job?>(null) }
    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { controller?.cancel(); catalogJob?.cancel() } }
    LaunchedEffect(draft.profileId, draft.endpoint, draft.source, keyDraft) {
        catalogJob?.cancel(); models = emptyList(); catalogStatus = null; loadingModels = false
    }
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
    fun refreshModels() {
        if (loadingModels) return
        val snapshot = draft
        val typedKey = keyDraft
        loadingModels = true; catalogStatus = null
        catalogJob = scope.launch {
            try {
                val key = if (typedKey.isNotBlank()) typedKey else withContext(Dispatchers.IO) {
                    credentialsFactory(context).read(snapshot.keySlotId)
                }
                models = fetchModels(snapshot.endpoint, key).getOrThrow().distinctBy { it.modelId }
                catalogStatus = if (models.isEmpty()) "目录未列出生图模型，可手动添加。" else "已读取 ${models.size} 个生图模型。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { catalogStatus = "无法读取生图模型目录，请检查 URL 和独立 Key；也可手动添加。" }
            finally { loadingModels = false }
        }
    }
    fun openModels() {
        if (stack.lastOrNull() != ImageGenRoute.Models) stack.add(ImageGenRoute.Models)
        if (models.isEmpty()) refreshModels()
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
    val back = { if (stack.size > 1) { stack.removeAt(stack.lastIndex); Unit } else onBack() }
    val dismiss = NavSwipeDirection.LeftToRight.takeIf { LocalAppearanceSettings.current.swipeDismissEnabled }
    NavDisplay(backStack = stack, onBack = back, effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius())) {
        entry<ImageGenRoute.Models>(swipeDismiss = dismiss) {
            ImageGenModelPickerScreen(models, draft.model, loadingModels, catalogStatus, back, ::refreshModels) { model ->
                draft = draft.copy(model = model); back()
            }
        }
        entry<ImageGenRoute.Connection>(swipeDismiss = dismiss) {
            ImageGenPage("生图配置", back, overlay = {
                val confirmRemoval = {
                    val accountId = removeAccount; val profile = draft
                    removeAccount = null; removeProfile = false
                    operation {
                        withContext(Dispatchers.IO) {
                            if (accountId != null) ProviderCredentialRepository.removeAccount(profile.credentialPoolId,
                                ProviderAuthModes.OPENAI_CHATGPT, accountId, AgentRunController())
                            else {
                                val credentials = credentialsFactory(context)
                                (profile.retiredCredentialIds + profile.keySlotId).distinct().forEach(credentials::delete)
                                ProviderCredentialRepository.forget(profile.credentialPoolId)
                                ImageGenConfigStore.remove(context, profile.profileId)
                            }
                        }
                        draft = if (accountId != null) draft.copy(accountId = draft.accountId.takeIf { it != accountId }.orEmpty()) else ImageGenConfig()
                    }
                }
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiConfirmDialog(visible = removeAccount != null || removeProfile, title = "确认从本机移除",
                        message = "只删除此独立连接或账号，不取消订阅，不影响聊天。", confirmText = "确认移除",
                        onConfirm = confirmRemoval, onDismissRequest = { removeAccount = null; removeProfile = false })
                    CouiConfirmDialog(visible = advanced, title = "完整 endpoint", message = null, confirmText = "保存",
                        onConfirm = { draft = draft.copy(endpointOverride = overrideDraft.trim()); advanced = false },
                        onDismissRequest = { advanced = false }) {
                        ImageGenField("完整 endpoint（留空自动生成）", overrideDraft, { overrideDraft = it })
                    }
                } else {
                    WindowDialog(show = removeAccount != null || removeProfile, title = "确认从本机移除",
                        onDismissRequest = { removeAccount = null; removeProfile = false }) {
                        ImageGenNote("只删除此独立连接或账号，不取消订阅，不影响聊天。")
                        MiuixDialogActions("确认移除", onConfirm = confirmRemoval,
                            onCancel = { removeAccount = null; removeProfile = false }, destructive = true)
                    }
                    WindowDialog(show = advanced, title = "完整 endpoint", onDismissRequest = { advanced = false }) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ImageGenField("完整 endpoint（留空自动生成）", overrideDraft, { overrideDraft = it })
                            MiuixDialogActions("保存", onConfirm = { draft = draft.copy(endpointOverride = overrideDraft.trim()); advanced = false },
                                onCancel = { advanced = false })
                        }
                    }
                }
            }) {
                item(key = "profiles") {
                    ImageGenGroup("独立生图连接") {
                        ImageGenDropdown("当前连接", listOf("未启用") + state.profiles.map { it.name.ifBlank { "未命名连接" } },
                            state.profiles.indexOfFirst { it.profileId == state.selectedId } + 1, !saving) { index ->
                            val selected = state.profiles.getOrNull(index - 1)
                            operation {
                                withContext(Dispatchers.IO) { ImageGenConfigStore.select(context, selected?.profileId) }
                                draft = selected ?: ImageGenConfig(); keyDraft = ""
                            }
                        }
                        ImageGenAction("新增生图连接", enabled = !saving, onClick = {
                            draft = ImageGenConfig(profileId = UUID.randomUUID().toString(), source = ImageGenSource.OPENAI_API,
                                name = "生图连接 ${state.profiles.size + 1}", model = ""); keyDraft = ""
                        })
                        if (state.profiles.isEmpty()) ImageGenNote("尚未创建连接。独立填写 API 或登录 Codex，无需创建语言模型服务商。")
                        if (runCatching { ImageGenConfigStore.legacy(context) }.getOrNull() != null)
                            ImageGenNote("旧生图配置已保留。请手动确认地址、Key 和账号后创建独立连接。")
                    }
                }
                if (draft.profileId.isNotBlank()) {
                    item(key = "editor") {
                        ImageGenGroup("连接配置") {
                            ImageGenDropdown("生图来源", listOf("独立 Images API", "Codex 订阅"),
                                if (draft.source == ImageGenSource.CODEX_SUBSCRIPTION) 1 else 0, !saving) {
                                draft = draft.copy(source = if (it == 0) ImageGenSource.OPENAI_API else ImageGenSource.CODEX_SUBSCRIPTION,
                                    accountId = "", model = if (it == 0) "" else "gpt-image-2")
                            }
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ImageGenField("连接名称", draft.name, { draft = draft.copy(name = it) }, !saving)
                                if (draft.source == ImageGenSource.OPENAI_API) {
                                    ImageGenField("中转 URL", draft.endpoint, { draft = draft.copy(endpoint = it) }, !saving)
                                    ImageGenField(if (draft.keyConfigured) "替换 API Key（留空保留）" else "独立 API Key",
                                        keyDraft, { keyDraft = it }, !saving, secret = true)
                                }
                            }
                            if (draft.source == ImageGenSource.OPENAI_API) {
                                ImageGenRow("OpenAI Images 兼容协议", "使用独立连接")
                                ImageGenRow("生图模型", draft.model.ifBlank { "点按选择模型" }, !saving, ::openModels)
                                ImageGenRow("刷新模型列表", if (loadingModels) "正在读取…" else "从此连接读取可用模型",
                                    !saving && !loadingModels, ::refreshModels)
                            } else {
                                ImageGenDropdown("固定 Codex 账号", listOf("请选择固定账号") + accounts.map {
                                    it.label + if (it.usable) "" else " · 需重新登录"
                                }, accounts.indexOfFirst { it.accountId == draft.accountId } + 1, !saving) {
                                    draft = draft.copy(accountId = accounts.getOrNull(it - 1)?.accountId.orEmpty())
                                }
                                accountError?.let { ImageGenNote(it) }
                                ImageGenAction("添加独立 Codex 账号", { login() }, !saving)
                                accounts.forEach { account ->
                                    ImageGenNote(account.label + if (account.usable) "" else " · 需要重新登录")
                                    ImageGenAction("重新登录", { login(account.accountId) }, !saving)
                                    ImageGenAction("从本机移除账号", { removeAccount = account.accountId }, !saving)
                                }
                                ImageGenRow("生图模型", "gpt-image-2 · Codex 订阅")
                            }
                        }
                    }
                    if (draft.source == ImageGenSource.OPENAI_API) item(key = "advanced") {
                        ImageGenNote("地址支持根 URL、/v1 或完整 Images endpoint，会自动补齐生成路径。")
                        ImageGenGroup("高级设置") {
                            ImageGenRow("完整 endpoint 覆盖", draft.endpointOverride.ifBlank { "默认自动生成" }, !saving) {
                                overrideDraft = draft.endpointOverride; advanced = true
                            }
                        }
                    }
                    item(key = "output") {
                        ImageGenGroup("图片选项") {
                            ImageGenDropdown("尺寸", ImageGenConfig.SIZES, ImageGenConfig.SIZES.indexOf(draft.size).coerceAtLeast(0), !saving) { draft = draft.copy(size = ImageGenConfig.SIZES[it]) }
                            ImageGenDropdown("质量", ImageGenConfig.QUALITIES, ImageGenConfig.QUALITIES.indexOf(draft.quality).coerceAtLeast(0), !saving) { draft = draft.copy(quality = ImageGenConfig.QUALITIES[it]) }
                            ImageGenDropdown("背景", ImageGenConfig.BACKGROUNDS, ImageGenConfig.BACKGROUNDS.indexOf(draft.background).coerceAtLeast(0), !saving) { draft = draft.copy(background = ImageGenConfig.BACKGROUNDS[it]) }
                        }
                    }
                }
                item(key = "status") {
                    error?.let { ImageGenNote(it) }; catalogStatus?.let { ImageGenNote(it) }
                    if (prompt != null) ImageGenAction("打开授权浏览器", {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt!!.authorizationUrl))) }
                        catch (_: Exception) { error = "[browser_launch] 无法打开授权浏览器"; controller?.cancel() }
                    })
                    if (saving && controller != null) ImageGenAction("取消登录", { controller?.cancel() })
                    if (draft.profileId.isNotBlank()) {
                        ImageGenNote(if (saving) "正在保存独立连接…" else "保存不会发起生图请求。连接固定使用本页模型和账号。")
                        ImageGenAction("保存连接", enabled = !saving, primary = true, onClick = {
                            val snapshot = draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim(),
                                keyConfigured = draft.keyConfigured || keyDraft.isNotBlank(),
                                credentialId = if (keyDraft.isNotBlank()) UUID.randomUUID().toString() else draft.credentialId,
                                retiredCredentialIds = if (keyDraft.isNotBlank() && draft.keyConfigured)
                                    (draft.retiredCredentialIds + draft.keySlotId).distinct() else draft.retiredCredentialIds)
                            val key = keyDraft
                            val problem = snapshot.validate()
                            if (problem != null) error = problem else operation {
                                withContext(Dispatchers.IO) {
                                    val credentials = credentialsFactory(context)
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
                        if (state.profiles.any { it.profileId == draft.profileId }) ImageGenAction("删除此生图连接", { removeProfile = true }, !saving)
                    }
                }
            }
        }
    }
}
