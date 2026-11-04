package io.github.mangi.eta.local.compaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.screens.subagent.SubagentModelPickerContent
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun CompactionSettingsScreen(onBack: () -> Unit) {
    val settingsFlow = remember { SettingsDataStore.compactionSettingsFlow() }
    val settings by settingsFlow.collectAsState(initial = CompactionSettings())
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryModel by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val primaryProvider by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun save(value: CompactionSettings) { scope.launch {
        runCatching { SettingsDataStore.setCompactionSettings(value) }
            .onSuccess { error = null }.onFailure { error = "保存失败：${it.javaClass.simpleName}" }
    } }
    val picker = remember(providers, primaryProvider, primaryModel) {
        AgentModelPickerProjector.project(providers, primaryProvider, primaryModel)
    }
    val target = remember(providers, settings.modelId, primaryModel) { providers.enabledModel(settings.modelId ?: primaryModel) }
    val capabilities = remember(target) { target?.let { (provider, model) -> RuntimeConfigRepository.reasoningCapabilities(provider, model) } }
    val efforts = remember(capabilities) { listOf<ReasoningEffort?>(null) + capabilities?.selectableEfforts.orEmpty() }
    var showModels by remember { mutableStateOf(false) }
    var draft by rememberSaveable(settings.prompt) { mutableStateOf(settings.prompt.ifBlank { AgentCompactionTarget.DEFAULT_PROMPT }) }
    MiuixScaffoldPage(title = "上下文压缩", onBack = onBack, overlay = {
        OverlayBottomSheet(show = showModels, title = "压缩模型", onDismissRequest = { showModels = false },
            insideMargin = DpSize(0.dp, 0.dp)) {
            SubagentModelPickerContent(pickerState = picker, selectedSecondaryModelId = settings.modelId,
                primaryModelName = picker.selectedModel?.displayName, onSelectModel = {
                    save(settings.copy(modelId = it)); showModels = false
                })
        }
    }) {
        item(key = "target") {
            Card(Modifier.padding(12.dp)) {
                ArrowPreference(title = "压缩模型", summary = when {
                    settings.modelId == null -> "跟随当前任务模型（主/子代理各自跟随）"
                    target != null -> "${target.provider.name} / ${target.model.displayName}"
                    else -> "所选模型不可用，请重新选择"
                }, onClick = { showModels = true })
                if (efforts.size > 1) OverlayDropdownPreference(title = "压缩思考强度",
                    items = efforts.map { it?.displayName ?: "跟随模型默认" },
                    selectedIndex = efforts.indexOf(ReasoningEffort.fromWireValue(settings.reasoningEffort)).coerceAtLeast(0),
                    onSelectedIndexChange = { save(settings.copy(reasoningEffort = efforts.getOrNull(it)?.wireValue)) })
            }
        }
        item(key = "privacy") {
            Text("主代理、子代理和手动/自动压缩统一使用此设置。所选服务商会接收待压缩历史；不改变任务本身的模型。小窗口按完整批次有界分段，失败保留原文，不自动换用其他模型。",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        item(key = "prompt") {
            Card(Modifier.padding(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("摘要策略提示词")
                    TextField(value = draft, onValueChange = { draft = it }, minLines = 8, maxLines = 16,
                        modifier = Modifier.fillMaxWidth())
                    TextButton(text = "保存提示词", onClick = { save(settings.copy(prompt = draft.trim())) })
                    TextButton(text = "恢复默认提示词", onClick = {
                        draft = AgentCompactionTarget.DEFAULT_PROMPT; save(settings.copy(prompt = ""))
                    })
                    Text("空白使用默认。无工具、完整非空输出、容量校验和失败保留原文由程序执行。")
                    error?.let { Text(it) }
                }
            }
        }
    }
}
