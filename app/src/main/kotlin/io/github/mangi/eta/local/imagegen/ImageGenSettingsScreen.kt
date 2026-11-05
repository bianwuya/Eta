package io.github.mangi.eta.local.imagegen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ImageGenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf(runCatching { ImageGenConfigStore.read(context) }
        .getOrElse { error = "生图配置无法读取，请重新保存"; ImageGenConfig() }) }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var accounts by remember { mutableStateOf<List<CodexAccountView>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var draftModel by rememberSaveable(config.model) { mutableStateOf(config.model) }
    LaunchedEffect(config.providerId, config.source, providers) {
        accounts = withContext(Dispatchers.IO) {
            if (config.source == ImageGenSource.CODEX_SUBSCRIPTION && config.providerId.isNotBlank()) {
                ProviderCredentialRepository.accounts(config.providerId, ProviderAuthModes.OPENAI_CHATGPT)
            } else emptyList()
        }
    }
    fun save(next: ImageGenConfig) {
        scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) { ImageGenConfigStore.write(context, next) }
                config = next; error = null
            } catch (_: Exception) { error = "生图配置保存失败，原配置未替换" }
            finally { saving = false }
        }
    }
    val sources = listOf(ImageGenSource.NONE, ImageGenSource.CODEX_SUBSCRIPTION, ImageGenSource.OPENAI_API)
    val selectable = providers.filterIsInstance<OpenAiCompatibleProviderSetting>().filter { provider ->
        provider.isEnabled && if (config.source == ImageGenSource.CODEX_SUBSCRIPTION) {
            provider.authMode == ProviderAuthModes.OPENAI_CHATGPT
        } else provider.authMode == ProviderAuthModes.API_KEY
    }
    val providerLabels = listOf("请选择固定服务商") + selectable.map { it.name }
    MiuixScaffoldPage(title = "生图配置", onBack = onBack) {
        item(key = "source") {
            SmallTitle("固定来源")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                OverlayDropdownPreference(title = "生图来源", items = listOf("未配置", "Codex 订阅", "OpenAI Images 兼容 API"),
                    selectedIndex = sources.indexOf(config.source).coerceAtLeast(0), enabled = !saving,
                    onSelectedIndexChange = { index -> save(config.copy(source = sources[index], providerId = "", accountId = "",
                        model = "gpt-image-2")) })
                if (config.source != ImageGenSource.NONE) {
                    OverlayDropdownPreference(title = "生图服务商", items = providerLabels, enabled = !saving,
                        selectedIndex = selectable.indexOfFirst { it.id == config.providerId } + 1,
                        onSelectedIndexChange = { index -> save(config.copy(providerId = selectable.getOrNull(index - 1)?.id.orEmpty(), accountId = "")) })
                    if (config.source == ImageGenSource.CODEX_SUBSCRIPTION) {
                        OverlayDropdownPreference(title = "固定 Codex 账号", enabled = !saving,
                            items = listOf("请选择固定账号") + accounts.map { account ->
                                (account.name.ifBlank { account.displayName ?: "Codex账号" }) + if (account.usable) "" else " · 需重新登录"
                            }, selectedIndex = accounts.indexOfFirst { it.accountId == config.accountId } + 1,
                            onSelectedIndexChange = { index -> save(config.copy(accountId = accounts.getOrNull(index - 1)?.accountId.orEmpty())) })
                    }
                }
            }
        }
        item(key = "parameters") {
            if (config.source != ImageGenSource.NONE) {
                SmallTitle("模型与输出")
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    if (config.source == ImageGenSource.CODEX_SUBSCRIPTION) {
                        Text("gpt-image-2 · Codex 订阅图片接口", modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(value = draftModel, onValueChange = { draftModel = it }, label = "生图模型", singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            TextButton(text = "保存模型", enabled = !saving && draftModel.isNotBlank() && draftModel != config.model,
                                onClick = { save(config.copy(model = draftModel.trim())) })
                        }
                    }
                    OverlayDropdownPreference(title = "尺寸", items = ImageGenConfig.SIZES, enabled = !saving,
                        selectedIndex = ImageGenConfig.SIZES.indexOf(config.size).coerceAtLeast(0),
                        onSelectedIndexChange = { save(config.copy(size = ImageGenConfig.SIZES[it])) })
                    OverlayDropdownPreference(title = "质量", items = ImageGenConfig.QUALITIES, enabled = !saving,
                        selectedIndex = ImageGenConfig.QUALITIES.indexOf(config.quality).coerceAtLeast(0),
                        onSelectedIndexChange = { save(config.copy(quality = ImageGenConfig.QUALITIES[it])) })
                    OverlayDropdownPreference(title = "背景", items = ImageGenConfig.BACKGROUNDS, enabled = !saving,
                        selectedIndex = ImageGenConfig.BACKGROUNDS.indexOf(config.background).coerceAtLeast(0),
                        onSelectedIndexChange = { save(config.copy(background = ImageGenConfig.BACKGROUNDS[it])) })
                }
            }
        }
        item(key = "status") {
            val selected = providers.firstOrNull { it.id == config.providerId }
            val state = config.validate() ?: when {
                selected == null || !selected.isEnabled -> "固定服务商已删除或停用"
                config.source == ImageGenSource.CODEX_SUBSCRIPTION && accounts.none { it.accountId == config.accountId && it.usable } -> "固定账号不可用，请重新登录或选择"
                else -> "配置已保存 · 接口可用性尚待真实生成验证"
            }
            Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state, style = MiuixTheme.textStyles.body2)
                error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = StatusError) }
                Text("生图始终使用此固定来源和账号，不随聊天模型或账号切换。真实调用会消耗该来源额度；失败不会改走其他收费来源。\n图片保存在 Downloads/Eta-输出/生成图片。兼容API须支持Images生成协议及Base64图片返回。",
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
