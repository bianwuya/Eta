package io.github.mangi.eta.local.imagegen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ImageGenModelPickerScreen(
    models: List<Model>, selected: String, loading: Boolean, status: String?, onBack: () -> Unit,
    onRefresh: () -> Unit, onSelected: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var manualModel by remember { mutableStateOf("") }
    val filtered = remember(models, search) { models.filter { it.modelId.contains(search, true) || it.displayName.contains(search, true) } }
    ImageGenPage("选择生图模型", onBack, overlay = {
        val valid = manualModel.trim().let { it.isNotBlank() && it.length <= 120 }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI)
            CouiConfirmDialog(visible = manual, title = "手动添加模型", message = null, confirmText = "选择", confirmEnabled = valid,
                onConfirm = { if (valid) { manual = false; onSelected(manualModel.trim()) } }, onDismissRequest = { manual = false }) {
                ImageGenField("模型 ID", manualModel, { manualModel = it })
                if (!valid) ImageGenNote("请输入 1–120 个字符的模型 ID。")
            }
        else WindowDialog(show = manual, title = "手动添加模型", onDismissRequest = { manual = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ImageGenField("模型 ID", manualModel, { manualModel = it })
                MiuixDialogActions("选择", confirmEnabled = valid, onCancel = { manual = false },
                    onConfirm = { manual = false; onSelected(manualModel.trim()) })
            }
        }
    }) {
        item(key = "search") { Column(Modifier.padding(16.dp)) { ImageGenField("搜索模型", search, { search = it }) } }
        item(key = "refresh") { ImageGenAction(if (loading) "正在读取模型…" else "刷新模型列表", onRefresh, !loading) }
        item(key = "catalog-status") {
            status?.let { ImageGenNote(it) }
            if (!loading && filtered.isEmpty()) ImageGenNote(if (models.isEmpty()) "目录未列出生图模型，可手动添加。" else "没有匹配的模型。")
        }
        itemsIndexed(filtered, key = { _, model -> model.modelId }) { index, model ->
            EtaPreferenceGroupItem(isFirst = index == 0, isLast = index == filtered.lastIndex) {
                ImageGenRow(model.modelId, if (model.modelId == selected) "已选择" else model.displayName) { onSelected(model.modelId) }
            }
        }
        item(key = "manual") {
            ImageGenGroup("其他") { ImageGenRow("手动添加模型", "目录未列出时使用") { manual = true } }
            ImageGenNote("模型列表来自此生图连接。选择后返回连接设置。")
        }
    }
}
