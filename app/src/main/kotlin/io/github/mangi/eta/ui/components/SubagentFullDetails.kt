package io.github.mangi.eta.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.subagent.SubagentDetailStore
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference

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
    LaunchedEffect(id, selected, page, refresh) {
        runCatching {
            withContext(Dispatchers.IO) {
                val header = store.header(id)
                val rows = if (selected == null) store.steps(id) else emptyList()
                val content = selected?.let { store.page(id, it, page) }
                val more = selected?.let { store.hasNext(id, it, page) } ?: false
                Triple(header != null, rows, content to more)
            }
        }.onSuccess {
            exists = it.first; steps = it.second; text = it.third.first; next = it.third.second; error = null
        }.onFailure { error = "详情读取失败：${it.javaClass.simpleName}" }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
        Row {
            if (selected != null) TextButton(text = "返回目录", onClick = { selected = null; page = 0 })
            TextButton(text = "刷新", onClick = { refresh++ })
            if (allowDelete) TextButton(text = "删除此任务详情", onClick = { scope.launch {
                runCatching { withContext(Dispatchers.IO) { store.clear(id) } }
                    .onSuccess { exists = false; steps = emptyList(); text = null }
                    .onFailure { error = "删除失败，请重试" }
            } })
        }
        error?.let { Text(it, color = StatusError) }
        if (!exists) Text("详情已清理或未保存；旧预览无法补成全文。", Modifier.padding(12.dp))
        if (selected == null && exists) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item { Text("脱敏保留内容。最多256步、单项128000字符；超限和敏感结果会标注，最多保留64项任务并按总量清理。", Modifier.padding(12.dp)) }
                item { ArrowPreference(title = "任务指令", onClick = { selected = "prompt"; page = 0 }) }
                item { ArrowPreference(title = "回复与交接报告", onClick = { selected = "output"; page = 0 }) }
                item { ArrowPreference(title = "错误详情", onClick = { selected = "error"; page = 0 }) }
                items(steps.filter { it.kind != SubagentStepSnapshot.KIND_THINKING }, key = { it.id }) { step ->
                    ArrowPreference(title = step.title.ifBlank { step.toolName }, summary = step.status,
                        onClick = { selected = "step-${step.id}"; page = 0 })
                }
            }
        } else if (selected != null && exists) {
            Row {
                TextButton(text = "上一页", onClick = { if (page > 0) page-- })
                Text("第 ${page + 1} 页", Modifier.padding(12.dp))
                if (next) TextButton(text = "下一页", onClick = { page++ })
                TextButton(text = "复制本页", onClick = {
                    text?.let { (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("Eta 子任务详情", it)) }
                })
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item { SelectionContainer { Text(text ?: "本项无保留正文或已达保留上限。", Modifier.padding(12.dp),
                    style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)) } }
            }
        }
    }
}
