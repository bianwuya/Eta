package io.github.mangi.eta.local.userquestions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class QuestionAction { PRESENTATION, SUBMIT, DEFER, REJECT }

internal fun firstInvalidQuestion(questions: List<UserQuestion>, drafts: List<QuestionDraft>): Int? =
    questions.indices.firstOrNull { index ->
        index !in drafts.indices || !UserQuestionParser.valid(questions[index], drafts[index])
    }

/**
 * The editor is keyed by the real form id, not by the currently visible tab or conversation.
 * Its finite debounce/write jobs survive leaving this composition so a quick conversation switch
 * cannot cancel the last keystroke. There is no service, polling or persisted execution recovery.
 */
private object QuestionEditors {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Main-thread access only; the map itself is not UI state and is never mutated
    // as a side effect observed by the composition.
    private val editors = mutableMapOf<String, QuestionEditor>()

    fun get(form: UserQuestionSet): QuestionEditor =
        editors.getOrPut(form.id) { QuestionEditor(form) }

    fun find(id: String): QuestionEditor? = editors[id]

    fun prune(forms: List<UserQuestionSet>) {
        val retainedIds = forms.mapTo(mutableSetOf()) { it.id }
        editors.keys.toList().filterNot { it in retainedIds }.forEach { editors.remove(it) }
    }
}

private class QuestionEditor(form: UserQuestionSet) {
    val id = form.id
    var drafts by mutableStateOf(form.drafts.toList())
        private set
    var selectedTab by mutableIntStateOf(0)
        private set
    val scroll = ScrollState(0)
    var scrollRequest by mutableIntStateOf(0)
        private set
    var action by mutableStateOf<QuestionAction?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var retrying by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set
    var historyExpanded by mutableStateOf(false)
    var historyDismissed by mutableStateOf(false)
    var receipt by mutableStateOf<String?>(null)
        private set

    private var revision = 0
    private var savedRevision = 0
    private var debounce: Job? = null
    private val writes = Mutex()

    val dirty: Boolean get() = revision != savedRevision
    val busy: Boolean get() = action != null

    fun selectTab(index: Int) {
        selectedTab = index
        scrollRequest++
    }

    fun edit(index: Int, change: (QuestionDraft) -> QuestionDraft) {
        if (busy || index !in drafts.indices) return
        val updated = change(drafts[index])
        if (updated == drafts[index]) return
        drafts = drafts.toMutableList().also { it[index] = updated }
        revision++
        actionError = null
        scheduleSave()
    }

    private fun scheduleSave() {
        debounce?.cancel()
        debounce = QuestionEditors.scope.launch {
            delay(300)
            // Only the timer is cancelled by a later edit; an AtomicFile write already in
            // progress finishes under the mutex before the next write or terminal operation.
            debounce = null
            saveLatest()
        }
    }

    private fun activeInStore(): Boolean = UserQuestionStore.forms.value.any {
        it.id == id && it.active && it.status == "waiting"
    }

    private suspend fun saveLatest() {
        writes.withLock {
            if (!dirty || busy) return@withLock
            if (!activeInStore()) {
                saveError = "原任务已结束，最新草稿未能保存"
                return@withLock
            }
            val snapshot = drafts.toList()
            val snapshotRevision = revision
            saving = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    UserQuestionStore.saveDrafts(id, snapshot)
                }
                if (saved) {
                    savedRevision = snapshotRevision
                    saveError = null
                } else {
                    saveError = if (activeInStore()) "草稿保存失败，请重试" else "原任务已结束，最新草稿未能保存"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "草稿保存失败，请重试"
            } finally {
                saving = false
            }
        }
    }

    fun retrySave() {
        if (busy || saving || retrying || !dirty) return
        retrying = true
        debounce?.cancel()
        debounce = null
        QuestionEditors.scope.launch {
            try { saveLatest() } finally { retrying = false }
        }
    }

    fun changePresentation(presentation: UserQuestionPresentation) {
        if (busy) return
        action = QuestionAction.PRESENTATION
        actionError = null
        QuestionEditors.scope.launch {
            try {
                writes.withLock {
                    val changed = withContext(Dispatchers.IO) {
                        UserQuestionStore.setPresentation(id, presentation)
                    }
                    if (!changed) actionError = "显示状态未保存，提问可能已结束"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = "显示状态保存失败，请重试"
            } finally {
                action = null
                if (dirty && activeInStore()) scheduleSave()
            }
        }
    }

    fun submit(form: UserQuestionSet) {
        if (busy) return
        val firstInvalid = firstInvalidQuestion(form.questions, drafts)
        if (firstInvalid != null) {
            selectTab(firstInvalid)
            actionError = "请先回答第 ${firstInvalid + 1} 题，可选择选项或填写文字"
            return
        }
        finish(QuestionAction.SUBMIT)
    }

    fun defer(reject: Boolean = false) {
        if (!busy) finish(if (reject) QuestionAction.REJECT else QuestionAction.DEFER)
    }

    private fun finish(request: QuestionAction) {
        if (busy) return
        // Set synchronously before launching: double taps cannot enqueue two completions.
        action = request
        actionError = null
        debounce?.cancel()
        debounce = null
        val snapshot = drafts.toList()
        val snapshotRevision = revision
        QuestionEditors.scope.launch {
            try {
                writes.withLock {
                    val completed = withContext(Dispatchers.IO) {
                        when (request) {
                            QuestionAction.SUBMIT -> UserQuestionStore.submit(id, snapshot)
                            QuestionAction.DEFER -> UserQuestionStore.defer(id, snapshot)
                            QuestionAction.REJECT -> UserQuestionStore.defer(
                                id, snapshot, "用户拒绝回答；未回答不代表同意",
                            )
                            QuestionAction.PRESENTATION -> false
                        }
                    }
                    if (completed) {
                        savedRevision = snapshotRevision
                        saveError = null
                        receipt = when (request) {
                            QuestionAction.SUBMIT -> "回答已提交"
                            QuestionAction.DEFER -> "已选择稍后；未回答不代表同意"
                            QuestionAction.REJECT -> "已拒绝提问；未回答不代表同意"
                            QuestionAction.PRESENTATION -> null
                        }
                        historyExpanded = false
                    } else {
                        actionError = when (request) {
                            QuestionAction.SUBMIT -> "未提交：原任务可能已结束或回答无效"
                            QuestionAction.DEFER -> "未完成稍后操作：原任务可能已结束"
                            QuestionAction.REJECT -> "未完成拒绝操作：原任务可能已结束"
                            QuestionAction.PRESENTATION -> "显示状态未保存，提问可能已结束"
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = when (request) {
                    QuestionAction.SUBMIT -> "提交失败，最新草稿仍在当前界面，请重试"
                    QuestionAction.DEFER -> "稍后操作失败，最新草稿仍在当前界面，请重试"
                    QuestionAction.REJECT -> "拒绝操作失败，最新草稿仍在当前界面，请重试"
                    QuestionAction.PRESENTATION -> "显示状态保存失败，请重试"
                }
            } finally {
                action = null
                if (receipt == null && dirty && activeInStore()) scheduleSave()
            }
        }
    }
}

/** Non-modal content only. The chat host supplies the Box position and available height. */
@Composable
internal fun UserQuestionsPanel(
    conversationId: String?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 320.dp,
) {
    if (conversationId == null) return
    val context = LocalContext.current
    var initializeError by remember { mutableStateOf<String?>(null) }
    var initializeAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context.applicationContext, initializeAttempt) {
        try {
            withContext(Dispatchers.IO) { UserQuestionStore.initialize(context.applicationContext) }
            initializeError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            initializeError = "无法读取提问草稿，原文件未覆盖"
        }
    }
    val forms by UserQuestionStore.forms.collectAsState()
    LaunchedEffect(forms) { QuestionEditors.prune(forms) }
    val initError = initializeError
    if (initError != null) {
        Card(modifier = modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(0.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(initError, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error)
                TextButton(text = "重试", onClick = { initializeAttempt++ })
            }
        }
        return
    }

    val owned = forms.filter { it.conversationId == conversationId }
    val pending = owned.filter { it.active && it.status == "waiting" }
    var chosenId by remember(conversationId) { mutableStateOf<String?>(null) }
    var knownPendingIds by remember(conversationId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pending.map { it.id }) {
        val newlyArrived = pending.lastOrNull { it.id !in knownPendingIds }
        knownPendingIds = pending.mapTo(mutableSetOf()) { it.id }
        if (newlyArrived != null && chosenId != null) chosenId = newlyArrived.id
    }
    val chosen = owned.firstOrNull { it.id == chosenId }
    // A form that ended while visible remains available as an explicitly read-only receipt.
    val chosenHistory = chosen?.takeIf {
        (!it.active || it.status != "waiting") && QuestionEditors.find(it.id)?.historyDismissed == false
    }
    val latestHistory = owned.lastOrNull { !it.active || it.status != "waiting" }
        ?.takeIf { QuestionEditors.find(it.id)?.historyDismissed != true }
    val form = pending.firstOrNull { it.id == chosenId }
        ?: chosenHistory
        ?: pending.lastOrNull()
        ?: latestHistory
        ?: return
    val editor = QuestionEditors.get(form)
    val active = form.active && form.status == "waiting"
    LaunchedEffect(form.id) { chosenId = form.id }
    LaunchedEffect(editor, editor.scrollRequest) {
        if (editor.scrollRequest > 0) editor.scroll.scrollTo(0)
    }
    LaunchedEffect(editor, active, editor.historyExpanded) {
        if (!active && !editor.historyExpanded) editor.scroll.scrollTo(0)
    }

    fun switchPending() {
        if (pending.isEmpty() || editor.busy) return
        val current = pending.indexOfFirst { it.id == form.id }
        chosenId = pending[(current + 1) % pending.size].id
    }

    BackHandler(enabled = active && form.presentation == UserQuestionPresentation.EXPANDED && !editor.busy) {
        editor.changePresentation(UserQuestionPresentation.COLLAPSED)
    }

    // Hiding removes the card itself. Only a low-occupancy recovery action remains;
    // there is no empty card surface or invisible modal hit region over the messages.
    if (active && form.presentation == UserQuestionPresentation.HIDDEN) {
        Column(modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(0.dp))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (pending.size > 1) {
                TextButton(text = "切换提问组 · ${pending.size}", onClick = ::switchPending, enabled = !editor.busy)
            }
            TextButton(text = "恢复提问 · ${form.questions.size} 题", enabled = !editor.busy,
                onClick = { editor.changePresentation(UserQuestionPresentation.EXPANDED) })
        }
        QuestionStatus(editor, active = true)
        }
        // A failed restore must stay visible rather than trapping a hidden waiter.
        return
    }

    if (!active) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight.coerceAtLeast(0.dp))
                .dropShadow(
                    shape = RoundedCornerShape(20.dp),
                    shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 20.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                    cornerRadius = 20.dp,
                ),
        ) {
            HistoryHeader(editor, pending, ::switchPending) {
                editor.historyDismissed = true
                chosenId = pending.lastOrNull()?.id
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(editor.scroll)) {
                HistoryStatus(form, editor)
                QuestionStatus(editor, active = false)
                if (editor.historyExpanded) {
                    HistoryBody(form, editor)
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.coerceAtLeast(0.dp))
            .dropShadow(
                shape = RoundedCornerShape(20.dp),
                shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f),
            )
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 20.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                cornerRadius = 20.dp,
            ),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight.coerceAtLeast(0.dp))
                .padding(vertical = 4.dp),
        ) {
            // If IME or large font leaves very little room, keep the whole card scrollable
            // rather than letting fixed actions consume every pixel of the question body.
            val scrollAll = this.maxHeight < 200.dp ||
                androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
            Column(Modifier.fillMaxWidth()) {
                if (form.presentation == UserQuestionPresentation.EXPANDED) {
                    if (scrollAll) {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(editor.scroll)) {
                            QuestionExpandedContent(form, editor, pending, ::switchPending)
                            QuestionStatus(editor, active = true)
                            QuestionActions(form, editor)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(editor.scroll)) {
                            QuestionExpandedContent(form, editor, pending, ::switchPending)
                        }
                        QuestionStatus(editor, active = true)
                        QuestionActions(form, editor)
                    }
                } else {
                    QuestionHeader(form, editor, pending, ::switchPending)
                    QuestionStatus(editor, active = true)
                }
            }
        }
    }
}

@Composable
private fun QuestionExpandedContent(
    form: UserQuestionSet,
    editor: QuestionEditor,
    pending: List<UserQuestionSet>,
    onSwitch: () -> Unit,
) {
    QuestionHeader(form, editor, pending, onSwitch)
    QuestionTabs(form, editor)
    QuestionBody(form, editor)
}

@Composable
private fun QuestionTabs(form: UserQuestionSet, editor: QuestionEditor) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        form.questions.forEachIndexed { index, question ->
            QuestionTab(
                index = index,
                title = question.tab,
                answered = editor.drafts.getOrNull(index)?.let { UserQuestionParser.valid(question, it) } == true,
                selected = editor.selectedTab == index,
                enabled = !editor.busy,
                onClick = { editor.selectTab(index) },
            )
        }
    }
}

@Composable
private fun QuestionTab(
    index: Int,
    title: String,
    answered: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val keyboardFocused = focused &&
        androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    Column(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                shape,
            )
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactions,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1} · $title",
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (answered) {
                Text(
                    text = " ✓",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (selected || keyboardFocused) 2.dp else 1.dp)
                .background(
                    when {
                        keyboardFocused -> MiuixTheme.colorScheme.primary
                        selected -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.dividerLine
                    },
                    shape,
                ),
        )
    }
}

@Composable
private fun QuestionActions(form: UserQuestionSet, editor: QuestionEditor) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(text = "稍后", onClick = { editor.defer() }, enabled = !editor.busy)
        TextButton(text = "拒绝", onClick = { editor.defer(reject = true) }, enabled = !editor.busy)
        TextButton(
            text = if (editor.action == QuestionAction.SUBMIT) "提交中…" else "提交回答",
            onClick = { editor.submit(form) },
            enabled = !editor.busy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun QuestionHeader(
    form: UserQuestionSet,
    editor: QuestionEditor,
    pending: List<UserQuestionSet>,
    onSwitch: () -> Unit,
) {
    val hidden = form.presentation == UserQuestionPresentation.HIDDEN
    val expanded = form.presentation == UserQuestionPresentation.EXPANDED
    val position = pending.indexOfFirst { it.id == form.id } + 1
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = (if (hidden) "已隐藏 · ${form.questions.size} 题" else "待回答 · ${form.questions.size} 题") +
                if (pending.size > 1) " · $position/${pending.size}组" else "",
            modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (pending.size > 1) {
            IconButton(onClick = onSwitch, enabled = !editor.busy, minWidth = 48.dp, minHeight = 48.dp) {
                Icon(imageVector = Icons.Rounded.SwapHoriz, contentDescription = "切换提问组，当前第 $position 组，共 ${pending.size} 组",
                    modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
        TextButton(
            text = when { hidden -> "恢复"; expanded -> "收起"; else -> "展开" },
            enabled = !editor.busy,
            onClick = {
                editor.changePresentation(if (expanded) UserQuestionPresentation.COLLAPSED else UserQuestionPresentation.EXPANDED)
            },
        )
        if (!hidden) {
            IconButton(onClick = { editor.changePresentation(UserQuestionPresentation.HIDDEN) },
                enabled = !editor.busy, minWidth = 48.dp, minHeight = 48.dp) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "隐藏提问，继续等待回答",
                    modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun QuestionBody(form: UserQuestionSet, editor: QuestionEditor) {
    if (form.intro.isNotBlank()) {
        Text(
            text = form.intro,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
    val index = editor.selectedTab.coerceIn(form.questions.indices)
    val question = form.questions[index]
    val draft = editor.drafts[index]
    Text(
        text = question.prompt,
        style = MiuixTheme.textStyles.body1,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
    )
    Text(
        text = if (question.multiple) "可多选，也可以直接填写" else "单选，也可以直接填写",
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
    )
    question.options.forEachIndexed { optionIndex, option ->
        val selected = optionIndex in draft.selected
        val shape = RoundedCornerShape(10.dp)
        val interactions = remember(form.id, index, optionIndex) { MutableInteractionSource() }
        val focused by interactions.collectIsFocusedAsState()
        val pressed by interactions.collectIsPressedAsState()
        val keyboardFocused = focused &&
            androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        val border = when {
            keyboardFocused -> MiuixTheme.colorScheme.primary
            selected -> MiuixTheme.colorScheme.primary.copy(alpha = 0.42f)
            else -> MiuixTheme.colorScheme.dividerLine
        }
        val fill = when {
            pressed -> MiuixTheme.colorScheme.primary.copy(alpha = if (selected) 0.18f else 0.07f)
            selected -> MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> Color.Transparent
        }
        fun toggle() {
            editor.edit(index) { current ->
                val next = if (question.multiple) {
                    if (optionIndex in current.selected) current.selected - optionIndex else current.selected + optionIndex
                } else if (optionIndex in current.selected) {
                    emptySet()
                } else {
                    setOf(optionIndex)
                }
                current.copy(selected = next)
            }
        }
        val selectionModifier = if (question.multiple) {
            Modifier.toggleable(
                value = selected,
                enabled = !editor.busy,
                role = Role.Checkbox,
                interactionSource = interactions,
                onValueChange = { toggle() },
            )
        } else {
            Modifier.selectable(
                selected = selected,
                enabled = !editor.busy,
                role = Role.RadioButton,
                interactionSource = interactions,
                onClick = ::toggle,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 1.dp)
                .clip(shape)
                .background(fill, shape)
                .border(1.dp, border, shape)
                .then(selectionModifier)
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptionIndicator(selected = selected, multiple = question.multiple)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(option.label, style = MiuixTheme.textStyles.body2)
                if (option.description.isNotBlank()) {
                    Text(
                        text = option.description,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
    Text(
        text = "直接回答或补充",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 2.dp),
    )
    TextField(
        value = draft.other,
        onValueChange = { text -> editor.edit(index) { it.copy(other = text.take(2000)) } },
        label = "可留空，支持多行",
        enabled = !editor.busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 40.dp),
        maxLines = 4,
    )
}

@Composable
private fun OptionIndicator(selected: Boolean, multiple: Boolean) {
    val shape = if (multiple) RoundedCornerShape(4.dp) else CircleShape
    val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(1.5.dp, color, shape)
            .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(if (multiple) 8.dp else 7.dp)
                    .background(color, if (multiple) RoundedCornerShape(2.dp) else CircleShape),
            )
        }
    }
}

@Composable
private fun QuestionStatus(editor: QuestionEditor, active: Boolean) {
    val status = when (editor.action) {
        QuestionAction.SUBMIT -> "正在提交回答…"
        QuestionAction.DEFER -> "正在保存并结束等待…"
        QuestionAction.REJECT -> "正在拒绝提问…"
        QuestionAction.PRESENTATION -> "正在保存显示状态…"
        null -> when {
            editor.saving || editor.retrying -> "正在保存草稿…"
            editor.actionError != null -> editor.actionError
            editor.saveError != null -> editor.saveError
            editor.dirty -> "草稿待保存…"
            else -> null
        }
    }
    if (status != null) {
        val isError = !editor.busy && !editor.saving && !editor.retrying &&
            (editor.actionError != null || editor.saveError != null)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2,
                color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (active && editor.saveError != null && editor.dirty) {
                TextButton(text = "重试保存", onClick = editor::retrySave,
                    enabled = !editor.busy && !editor.saving && !editor.retrying)
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    editor: QuestionEditor,
    pending: List<UserQuestionSet>,
    onSwitch: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("历史提问", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
        if (pending.isNotEmpty()) {
            IconButton(onClick = onSwitch, enabled = !editor.busy, minWidth = 48.dp, minHeight = 48.dp) {
                Icon(imageVector = Icons.Rounded.SwapHoriz, contentDescription = "切换到待回答提问，共 ${pending.size} 组",
                    modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
        IconButton(
            onClick = { editor.historyExpanded = !editor.historyExpanded },
            modifier = Modifier.semantics {
                stateDescription = if (editor.historyExpanded) "已展开" else "已收起"
            },
            enabled = !editor.busy,
            minWidth = 48.dp,
            minHeight = 48.dp,
        ) {
            Text(
                text = if (editor.historyExpanded) "收起" else "查看",
                style = MiuixTheme.textStyles.footnote1,
                color = if (editor.busy) MiuixTheme.colorScheme.disabledOnSecondaryVariant else MiuixTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onClose, enabled = !editor.busy, minWidth = 48.dp, minHeight = 48.dp) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "关闭历史提示", modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun HistoryStatus(form: UserQuestionSet, editor: QuestionEditor) {
    val status = when {
        editor.action == QuestionAction.SUBMIT -> "正在提交回答…"
        editor.action == QuestionAction.DEFER -> "正在保存并结束等待…"
        form.endReason.isNotBlank() -> form.endReason
        editor.receipt != null -> editor.receipt!!
        form.status == "answered" -> "已回答"
        form.status == "unanswered" -> "未回答，等待已结束"
        else -> "提问已过期 · 原任务已结束"
    }
    Text(
        text = status,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
    )
}

@Composable
private fun HistoryBody(form: UserQuestionSet, editor: QuestionEditor) {
    Text("仅查看历史和草稿；原等待不会因查看而恢复，未回答不代表同意。",
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    if (editor.dirty) {
        Text("以下包含尚未保存的当前界面内容。", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
    form.questions.forEachIndexed { index, question ->
        val draft = editor.drafts.getOrNull(index) ?: return@forEachIndexed
        val selected = draft.selected.sorted().mapNotNull { question.options.getOrNull(it)?.label }
        Text("${index + 1}. ${question.prompt}", style = MiuixTheme.textStyles.body1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (selected.isNotEmpty()) Text("已选：${selected.joinToString("、")}", style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        if (draft.other.isNotBlank()) Text("补充：${draft.other}", style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        if (selected.isEmpty() && draft.other.isBlank()) Text("未填写", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
}
