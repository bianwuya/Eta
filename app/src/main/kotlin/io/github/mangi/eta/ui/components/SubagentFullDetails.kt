package io.github.mangi.eta.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import io.github.mangi.eta.agent.subagent.SubagentDetailStore
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SubagentFullDetails(id: String, allowDelete: Boolean) {
    val context = LocalContext.current
    val store = remember(id) { SubagentDetailStore(context) }
    val scope = rememberCoroutineScope()
    var steps by remember(id) { mutableStateOf<List<SubagentStepSnapshot>>(emptyList()) }
    var selected by remember(id) { mutableStateOf<String?>(null) }
    var page by remember(id) { mutableIntStateOf(0) }
    var text by remember(id) { mutableStateOf<String?>(null) }
    var next by remember(id) { mutableStateOf(false) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    var exists by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.72f
    BackHandler(enabled = selected != null) { selected = null; page = 0 }
    LaunchedEffect(id, selected, page, refresh) {
        loading = true
        text = null
        next = false
        runCatching {
            withContext(Dispatchers.IO) {
                val header = store.header(id)
                val rows = if (selected == null) store.steps(id) else steps
                val content = selected?.let { store.page(id, it, page) }
                val more = selected?.let { store.hasNext(id, it, page) } ?: false
                Triple(header != null, rows, content to more)
            }
        }.onSuccess {
            exists = it.first; steps = it.second; text = it.third.first; next = it.third.second; error = null
        }.onFailure { error = "详情读取失败：${it.javaClass.simpleName}" }
        loading = false
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
        SubagentDetailsToolbar(
            title = when (selected) {
                null -> "保留内容"
                "prompt" -> "任务指令"
                "output" -> "回复与交接报告"
                "error" -> "错误详情"
                else -> steps.firstOrNull { "step-${it.id}" == selected }?.title ?: "工具详情"
            },
            onBack = if (selected == null) null else ({ selected = null; page = 0 }),
            copyEnabled = !loading && text != null,
            onCopy = if (selected == null) null else ({
                text?.let { context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Eta 子任务详情", it)) }
            }),
            refreshEnabled = !loading,
            onRefresh = { refresh++ },
            deleteEnabled = exists,
            onDelete = if (selected == null && allowDelete) ({ confirmDelete = true }) else null,
        )
        error?.let { Text(it, color = StatusError) }
        AnimatedContent(
            targetState = selected,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "subagent_detail_content",
        ) { entry ->
            when {
                !exists -> ListEmptyState(title = "详情未保留或已清理")
                loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(size = 24.dp)
                }
                entry == null -> LazyColumn(Modifier.fillMaxWidth().overScrollVertical()) {
                    item { ArrowPreference(title = "任务指令", onClick = { selected = "prompt"; page = 0 }) }
                    item { ArrowPreference(title = "回复与交接报告", onClick = { selected = "output"; page = 0 }) }
                    item { ArrowPreference(title = "错误详情", onClick = { selected = "error"; page = 0 }) }
                    items(steps.filter { it.kind != SubagentStepSnapshot.KIND_THINKING }, key = { it.id }) { step ->
                        ArrowPreference(title = step.title.ifBlank { step.toolName }, summary = step.status,
                            onClick = { selected = "step-${step.id}"; page = 0 })
                    }
                }
                else -> key(entry, page) {
                    LazyColumn(Modifier.fillMaxWidth().overScrollVertical()) {
                        item { SelectionContainer {
                            Text(text ?: "本项无保留正文或已达保留上限。", Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace))
                        } }
                    }
                }
            }
        }
        if (selected != null && exists) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !loading && page > 0, onClick = { page-- }) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, contentDescription = "上一页")
                }
                Text("第 ${page + 1} 页", Modifier.padding(horizontal = 24.dp), style = MiuixTheme.textStyles.footnote1)
                IconButton(enabled = !loading && next, onClick = { page++ }) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, contentDescription = "下一页")
                }
            }
        }
    }
    WindowDialog(show = confirmDelete, title = "删除任务详情", onDismissRequest = { confirmDelete = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("仅删除本机保留的任务详情，不影响聊天记录。")
            MiuixDialogActions(confirmText = "删除", destructive = true, onCancel = { confirmDelete = false }, onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.clear(id) } }
                        .onSuccess { exists = false; steps = emptyList(); text = null }
                        .onFailure { error = "删除失败，请重试" }
                }
            })
        }
    }
}

@Composable
internal fun SubagentDetailsToolbar(
    title: String,
    onBack: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    copyEnabled: Boolean = true,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回详情目录")
        }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MiuixTheme.textStyles.body1,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onCopy != null) IconButton(enabled = copyEnabled, onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "复制本页")
        }
        IconButton(enabled = refreshEnabled, onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新任务详情")
        }
        if (onDelete != null) IconButton(enabled = deleteEnabled, onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除此任务详情")
        }
    }
}
