package io.github.mangi.eta.local.compaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.AgentCompactionTarget
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.CompactionSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.screens.subagent.SubagentModelPickerContent
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun CompactionSettingsScreen(onBack: () -> Unit) {
    val settingsFlow = remember { SettingsDataStore.compactionSettingsFlow() }
    val settings by settingsFlow.collectAsState(initial = CompactionSettings())
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryModel by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val primaryProvider by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun save(value: CompactionSettings) { scope.launch {
        saving = true
        runCatching { SettingsDataStore.setCompactionSettings(value) }
            .onSuccess { error = null }.onFailure { error = "保存失败：${it.javaClass.simpleName}" }
        saving = false
    } }
    val picker = remember(providers, primaryProvider, primaryModel) {
        AgentModelPickerProjector.project(providers, primaryProvider, primaryModel)
    }
    val target = remember(providers, settings.modelId, primaryModel) { providers.enabledModel(settings.modelId ?: primaryModel) }
    val capabilities = remember(target) { target?.let { (provider, model) -> RuntimeConfigRepository.reasoningCapabilities(provider, model) } }
    val efforts = remember(capabilities) { listOf<ReasoningEffort?>(null) + capabilities?.selectableEfforts.orEmpty() }
    var showModels by remember { mutableStateOf(false) }
    var draft by rememberSaveable(settings.prompt) { mutableStateOf(settings.prompt.ifBlank { AgentCompactionTarget.DEFAULT_PROMPT }) }
    var confirmReset by remember { mutableStateOf(false) }
    val effectivePrompt = settings.prompt.ifBlank { AgentCompactionTarget.DEFAULT_PROMPT }
    MiuixScaffoldPage(title = "上下文压缩", onBack = onBack, overlay = {
        OverlayBottomSheet(show = showModels, title = "压缩模型", onDismissRequest = { showModels = false },
            insideMargin = DpSize(0.dp, 0.dp)) {
            SubagentModelPickerContent(pickerState = picker, selectedSecondaryModelId = settings.modelId,
                primaryModelName = picker.selectedModel?.displayName, onSelectModel = {
                    save(settings.copy(modelId = it)); showModels = false
                })
        }
        WindowDialog(show = confirmReset, title = "恢复默认提示词", onDismissRequest = { confirmReset = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("当前摘要策略将替换为默认提示词。", style = MiuixTheme.textStyles.body2)
                MiuixDialogActions(confirmText = "恢复默认", onCancel = { confirmReset = false }, onConfirm = {
                    draft = AgentCompactionTarget.DEFAULT_PROMPT
                    save(settings.copy(prompt = ""))
                    confirmReset = false
                })
            }
        }
    }) {
        item(key = "target") {
            SmallTitle("模型与推理")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(title = "压缩模型", summary = when {
                    settings.modelId == null -> "跟随当前任务模型（主/子代理各自跟随）"
                    target != null -> "${target.provider.name} / ${target.model.displayName}"
                    else -> "所选模型不可用，请重新选择"
                }, onClick = { showModels = true })
                AnimatedVisibility(visible = efforts.size > 1,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
                    OverlayDropdownPreference(title = "压缩思考强度",
                        items = efforts.map { it?.displayName ?: "跟随模型默认" },
                        selectedIndex = efforts.indexOf(ReasoningEffort.fromWireValue(settings.reasoningEffort)).coerceAtLeast(0),
                        onSelectedIndexChange = { save(settings.copy(reasoningEffort = efforts.getOrNull(it)?.wireValue)) })
                }
            }
        }
        item(key = "privacy") {
            Text("所选服务商会接收待压缩历史；失败保留原文。",
                Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        item(key = "prompt") {
            SmallTitle("摘要策略")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = draft, onValueChange = { draft = it }, minLines = 6, maxLines = 12,
                        label = "压缩提示词",
                        modifier = Modifier.fillMaxWidth())
                }
                ArrowPreference(title = "恢复默认提示词", enabled = !saving, onClick = { confirmReset = true })
            }
        }
        item(key = "save") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = if (saving) "正在保存" else "保存提示词",
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = !saving && draft != effectivePrompt,
                    onClick = { save(settings.copy(prompt = draft.trim())) })
                error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = StatusError,
                    modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}
