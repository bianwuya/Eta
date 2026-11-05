package io.github.mangi.eta.local.userquestions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UserQuestionsPanel(conversationId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { kotlinx.coroutines.withContext(Dispatchers.IO) { UserQuestionStore.initialize(context) } }
        catch (_: Exception) { error = "无法读取提问草稿，原文件未覆盖" }
    }
    val forms by UserQuestionStore.forms.collectAsState()
    if (conversationId == null) return
    val owned = forms.filter { it.conversationId == conversationId }
    val form = owned.lastOrNull { it.active } ?: owned.lastOrNull() ?: return
    val localDrafts = remember(form.id) { mutableStateListOf(*form.drafts.toTypedArray()) }
    val writes = remember(form.id) { kotlinx.coroutines.sync.Mutex() }
    var expanded by remember(form.id) { mutableStateOf(form.active) }
    var selectedTab by remember(form.id) { mutableIntStateOf(0) }
    LaunchedEffect(form.status) { if (form.status == "answered") expanded = false }
    fun edit(index: Int, draft: QuestionDraft) {
        localDrafts[index] = draft
        scope.launch {
            writes.lock()
            try { kotlinx.coroutines.withContext(Dispatchers.IO) { UserQuestionStore.draft(form.id, index, draft) } }
            catch (_: Exception) { error = "答案草稿保存失败，请重试" }
            finally { writes.unlock() }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (!expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                Text(when (form.status) {
                    "answered" -> "已回答 · ${form.summary}"
                    "waiting" -> "有问题待回答"
                    else -> "未回答 · 任务已结束，草稿保留"
                }, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(text = "展开", onClick = { expanded = true })
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                TabRow(tabs = form.questions.map { it.tab }, selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it }, modifier = Modifier.weight(1f))
                TextButton(text = "收起", onClick = { expanded = false })
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (form.intro.isNotBlank()) Text(form.intro, style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                val index = selectedTab.coerceIn(form.questions.indices)
                val q = form.questions[index]
                val draft = localDrafts[index]
                Text(q.prompt + if (q.multiple) "（多选）" else "", style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                q.options.forEachIndexed { optionIndex, option ->
                    val selected = optionIndex in draft.selected
                    fun toggle() {
                        val next = if (q.multiple) {
                            if (selected) draft.selected - optionIndex else draft.selected + optionIndex
                        } else if (selected) emptySet() else setOf(optionIndex)
                        edit(index, draft.copy(selected = next, otherSelected = if (q.multiple) draft.otherSelected else false))
                    }
                    BasicComponent(title = option.label, summary = option.description.takeIf { it.isNotBlank() },
                        enabled = form.active, onClick = ::toggle,
                        endActions = { Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off,
                            enabled = form.active, onClick = ::toggle) })
                }
                if (q.allowOther) {
                    BasicComponent(title = "其他 / 自己填写", enabled = form.active,
                        onClick = { edit(index, draft.copy(otherSelected = !draft.otherSelected,
                            selected = if (q.multiple) draft.selected else emptySet())) },
                        endActions = { Checkbox(state = if (draft.otherSelected) ToggleableState.On else ToggleableState.Off,
                            enabled = form.active, onClick = { edit(index, draft.copy(otherSelected = !draft.otherSelected,
                                selected = if (q.multiple) draft.selected else emptySet())) }) })
                    if (draft.otherSelected) TextField(value = draft.other,
                        onValueChange = { edit(index, draft.copy(other = it.take(2000))) }, label = "补充回答",
                        enabled = form.active, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), maxLines = 4)
                }
                error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)) }
                if (form.active) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "稍后", modifier = Modifier.weight(1f), onClick = { scope.launch(Dispatchers.IO) {
                        try { UserQuestionStore.dismiss(form.id) } catch (_: Exception) { }
                    } })
                    if (index > 0) TextButton(text = "上一题", onClick = { selectedTab-- })
                    if (index < form.questions.lastIndex) TextButton(text = "下一题", onClick = { selectedTab++ }, modifier = Modifier.weight(1f))
                    else TextButton(text = "提交回答", modifier = Modifier.weight(1f),
                        enabled = localDrafts.toList() == form.drafts && form.questions.indices.all { UserQuestionParser.valid(form.questions[it], localDrafts[it]) },
                        onClick = { scope.launch(Dispatchers.IO) {
                            try {
                                if (!UserQuestionStore.submit(form.id)) kotlinx.coroutines.withContext(Dispatchers.Main) { error = "原任务已结束或回答未填写完整，未提交" }
                            } catch (_: Exception) { kotlinx.coroutines.withContext(Dispatchers.Main) { error = "回答保存失败，未提交，请重试" } }
                        } })
                } else Text(if (form.status == "answered") form.summary else "原任务已结束；草稿保留，未回答不代表同意。",
                    style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
