package io.github.mangi.eta.local.planmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class PlanActionRequest { APPROVE, EDIT, DEFER }

internal fun planEditValidation(baseRevision: Int, revision: Int, content: String): String? = when {
    baseRevision != revision -> "计划版本已变化；保留当前编辑内容，请复制后重新打开最新版本。"
    content.isBlank() || content.length > 40000 -> "正文不能为空，且不能超过 40000 字符。"
    else -> null
}

internal fun unreviewedPlanSteps(
    plan: PlanRecord,
    reviewedPlanId: String?,
    reviewedRevision: Int,
    reviewedSteps: Set<String>,
): Int = plan.steps.count {
    it.actions.isNotEmpty() && (reviewedPlanId != plan.planId || reviewedRevision != plan.revision || it.id !in reviewedSteps)
}

/**
 * Short one-shot UI writes outlive the current LazyColumn item. No polling or service is used.
 * The actual (planId, revision) is captured before dispatch; the store independently rechecks it.
 */
private object PlanEditors {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val values = mutableMapOf<String, PlanEditor>()
    fun get(plan: PlanRecord): PlanEditor = values.getOrPut(plan.planId) { PlanEditor(plan.planId) }
    fun prune(plans: List<PlanRecord>) {
        val retained = plans.mapTo(mutableSetOf()) { it.planId }
        values.keys.toList().filterNot { it in retained || values[it]?.busy == true }.forEach { values.remove(it) }
    }
}

private class PlanEditor(private val planId: String) {
    var expanded by mutableStateOf(false)
    var editing by mutableStateOf(false)
    var draft by mutableStateOf("")
    var baseRevision by mutableIntStateOf(0)
    private var openStepRevision by mutableIntStateOf(-1)
    private var openSteps by mutableStateOf<Set<String>>(emptySet())
    private var reviewedRevision by mutableIntStateOf(-1)
    private var reviewedSteps by mutableStateOf<Set<String>>(emptySet())
    var action by mutableStateOf<PlanActionRequest?>(null)
    var error by mutableStateOf<String?>(null)
    val busy get() = action != null

    fun isStepOpen(revision: Int, stepId: String): Boolean = openStepRevision == revision && stepId in openSteps
    fun toggleStep(revision: Int, stepId: String) {
        val current = if (openStepRevision == revision) openSteps else emptySet()
        openStepRevision = revision
        openSteps = if (stepId in current) current - stepId else current + stepId
    }

    fun markVisible(revision: Int, stepId: String) {
        val current = if (reviewedRevision == revision) reviewedSteps else emptySet()
        reviewedRevision = revision
        reviewedSteps = current + stepId
    }

    fun unreviewed(plan: PlanRecord): Int = unreviewedPlanSteps(plan, planId, reviewedRevision, reviewedSteps)

    fun beginEdit(plan: PlanRecord) {
        baseRevision = plan.revision
        draft = plan.content
        editing = true
        error = null
    }

    fun finishEdit() { editing = false; error = null }

    fun perform(request: PlanActionRequest, plan: PlanRecord) {
        if (busy) return
        if (request == PlanActionRequest.APPROVE && unreviewed(plan) > 0) {
            error = "请先展开当前版本每个步骤的完整操作与参数。"
            return
        }
        if (request == PlanActionRequest.EDIT) {
            val validation = planEditValidation(baseRevision, plan.revision, draft)
            if (validation != null) {
                error = validation
                return
            }
        }
        action = request
        error = null
        val id = plan.planId
        val revision = plan.revision
        val content = draft
        PlanEditors.scope.launch {
            try {
                val changed = withContext(Dispatchers.IO) {
                    when (request) {
                        PlanActionRequest.APPROVE -> PlanModeStore.approve(id, revision)
                        PlanActionRequest.EDIT -> PlanModeStore.edit(id, revision, content)
                        PlanActionRequest.DEFER -> PlanModeStore.defer(id, revision)
                    }
                }
                if (!changed) {
                    error = "未操作：版本可能已更新、等待已超时或任务已结束；请查看最新状态。"
                } else if (request == PlanActionRequest.EDIT) {
                    editing = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // AtomicFile readback can fail after the write has reached disk; do not blindly
                // repeat an approval/edit or claim it was not applied. The store rechecks versions.
                error = "保存结果未确认，请查看当前版本及状态后重试。"
            } finally {
                action = null
            }
        }
    }
}

/** Called inside the existing chat LazyColumn; never creates a route, overlay, or nested scroller. */
@Composable
internal fun PlanConversationPanel(conversationId: String?, modifier: Modifier = Modifier) {
    if (conversationId.isNullOrBlank()) return
    val context = LocalContext.current.applicationContext
    var initializeError by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, attempt) {
        try {
            withContext(Dispatchers.IO) { PlanModeStore.initialize(context) }
            initializeError = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            initializeError = true
        }
    }
    val plans by PlanModeStore.plans.collectAsState()
    LaunchedEffect(plans) { PlanEditors.prune(plans) }
    if (initializeError) {
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("无法读取计划记录，原文件未覆盖。", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error)
                TextButton(text = "重试", onClick = { attempt++ })
            }
        }
        return
    }
    val owned = plans.filter { it.conversationId == conversationId }
    if (owned.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        owned.forEach { plan -> PlanCard(plan, PlanEditors.get(plan)) }
    }
}

@Composable
private fun PlanCard(plan: PlanRecord, editor: PlanEditor) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(plan.title, style = MiuixTheme.textStyles.body1)
            Text("版本 ${plan.revision} · ${planStatus(plan)} · 阶段：${planPhase(plan.phase)}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (plan.revision > 1) {
                Text("此前版本的批准不适用于此版本。", style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (!plan.active) {
                Text("原运行已结束或进程已重启；仅供查看，不能恢复原等待或批准。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (plan.persistenceWarning.isNotBlank()) {
                Text(plan.persistenceWarning, style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            }
            if (plan.interruptedWork) {
                Text("计划变更前已有操作在途；无法撤销已发出的操作，请核实结果。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (plan.editPending) {
                Text("编辑仅保存了修改；等待后端重新规划具体步骤和操作，不构成批准。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton(text = if (editor.expanded) "收起计划" else "查看完整计划与操作",
                onClick = { editor.expanded = !editor.expanded }, enabled = !editor.busy)
            if (editor.expanded) PlanDetails(plan, editor)
        }
    }
}

@Composable
private fun PlanDetails(plan: PlanRecord, editor: PlanEditor) {
    Text("计划 ID：${plan.planId}", style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text("问题依赖阻塞：${if (plan.questionBlocked) "是" else "否"}。未回答或稍后不代表批准。",
        style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(top = 6.dp))
    if (editor.editing) {
        if (editor.baseRevision != plan.revision) {
            Text("正在编辑的版本 ${editor.baseRevision} 已过期；以下草稿仅供复制，不会覆盖新版。",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
        }
        TextField(value = editor.draft, onValueChange = { editor.draft = it },
            label = "编辑计划正文", enabled = !editor.busy,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), maxLines = 8)
        // Separate rows remain reachable with narrow screens and large font scales.
        TextButton(text = if (editor.action == PlanActionRequest.EDIT) "保存中…" else "保存修改并重新规划",
            onClick = { editor.perform(PlanActionRequest.EDIT, plan) },
            enabled = !editor.busy && plan.active && editor.baseRevision == plan.revision)
        TextButton(text = "取消编辑", onClick = editor::finishEdit, enabled = !editor.busy)
    } else {
        Text("计划正文", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 8.dp))
        Text(plan.content, style = MiuixTheme.textStyles.body1, softWrap = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
    Text("步骤与实际操作", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 8.dp))
    Text("确认前请展开每个步骤，逐项查看工具名与完整参数。", style = MiuixTheme.textStyles.footnote2)
    if (plan.active && plan.status == "awaiting_approval") {
        Text("本次确认最多等待 10 分钟；超时只保留计划，不视为同意。", style = MiuixTheme.textStyles.footnote2)
    }
    if (plan.steps.isEmpty()) {
        Text("当前版本尚无可批准的具体步骤；请等待重新规划。", style = MiuixTheme.textStyles.footnote2)
    }
    plan.steps.forEachIndexed { index, step -> PlanStepDetails(plan, editor, index, step) }
    val unreviewed = editor.unreviewed(plan)
    if (plan.active && plan.status == "awaiting_approval" && unreviewed > 0) {
        Text("尚有 $unreviewed 个步骤的操作未展开；展开全部完整参数后才能确认当前版本。",
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
    editor.error?.let {
        Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 6.dp))
    }
    if (!editor.editing && plan.active) {
        TextButton(text = "修改计划（不批准）", onClick = { editor.beginEdit(plan) }, enabled = !editor.busy)
        if (plan.status == "awaiting_approval" && plan.steps.isNotEmpty()) {
            TextButton(text = if (editor.action == PlanActionRequest.DEFER) "处理中…" else "稍后（不批准）",
                onClick = { editor.perform(PlanActionRequest.DEFER, plan) }, enabled = !editor.busy)
            TextButton(text = if (editor.action == PlanActionRequest.APPROVE) "确认中…" else "确认以上完整操作 · 版本 ${plan.revision}",
                onClick = { editor.perform(PlanActionRequest.APPROVE, plan) }, enabled = !editor.busy && unreviewed == 0,
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun PlanStepDetails(plan: PlanRecord, editor: PlanEditor, index: Int, step: PlanStep) {
    val expanded = editor.isStepOpen(plan.revision, step.id)
    Text("${index + 1}. ${step.title}", style = MiuixTheme.textStyles.body1,
        modifier = Modifier.padding(top = 8.dp))
    Text("步骤 ID：${step.id} · 状态：${stepStatus(step.status)}", style = MiuixTheme.textStyles.footnote2)
    Text("依赖：${step.dependsOn.joinToString("、").ifEmpty { "无" }}", style = MiuixTheme.textStyles.footnote2)
    Text("明确独立于待答问题：${if (step.independentOfQuestions) "是" else "否"}",
        style = MiuixTheme.textStyles.footnote2)
    if (step.actions.isEmpty()) {
        Text("此步骤未列出工具操作。", style = MiuixTheme.textStyles.footnote2)
    } else {
        TextButton(text = if (expanded) "收起此步骤操作" else "查看此步骤全部 ${step.actions.size} 项操作与参数",
            onClick = { editor.toggleStep(plan.revision, step.id) })
        if (expanded) {
            // This is an exposure gate, not proof of comprehension or a per-step approval.
            LaunchedEffect(plan.planId, plan.revision, step.id) { editor.markVisible(plan.revision, step.id) }
            step.actions.forEachIndexed { actionIndex, action ->
                Text("操作 ${actionIndex + 1} · 工具：${action.toolName}", style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 4.dp))
                Text("完整参数：\n${action.arguments}", style = MiuixTheme.textStyles.body2, softWrap = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
}

private fun planStatus(plan: PlanRecord): String = when (plan.status) {
    "draft" -> "草稿，待重新规划"
    "awaiting_approval" -> if (plan.active) "待确认" else "等待已结束，未批准"
    "approved" -> if (plan.active) "已确认当前版本" else "原运行已结束"
    "unanswered" -> "未确认（稍后或等待超时）"
    "ended" -> "原运行已结束"
    "superseded" -> "旧版，已失效"
    "cancelled" -> "已取消"
    else -> "状态：${plan.status}"
}
private fun planPhase(phase: String): String = when (phase) {
    "planning" -> "规划中"
    "running" -> "执行中"
    "partially_blocked" -> "部分阻塞"
    "verifying" -> "验证中"
    "completed" -> "已完成"
    "ended" -> "已结束"
    else -> phase
}
private fun stepStatus(status: String): String = when (status) {
    "pending" -> "待执行"
    "running" -> "执行中"
    "waiting_child" -> "等待子任务最终报告（启动已确认）"
    "completed" -> "已完成"
    "blocked" -> "阻塞"
    "failed" -> "失败"
    else -> status
}
