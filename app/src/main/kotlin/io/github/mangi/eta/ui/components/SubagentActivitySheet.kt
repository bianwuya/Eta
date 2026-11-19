package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import io.github.mangi.eta.local.subagentdisplay.SubagentReadableText
import io.github.mangi.eta.local.subagentstats.rememberSubagentSettingsSummary
import io.github.mangi.eta.ui.components.coui.CouiPreference
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天时间线中的 Subagent 节点行：保持内联极简与留白，点击呼出实时检视面板。
 */
@Composable
internal fun SubagentActivityRow(
    message: ToolActivityMessageUi,
    snapshot: SubagentActivitySnapshot,
    elapsedSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    var showInspector by rememberSaveable(message.id) { mutableStateOf(false) }
    var showFullDetails by rememberSaveable(message.id) { mutableStateOf(false) }
    val running = message.status == ToolActivityStatusUi.Running &&
        snapshot.status == SubagentActivitySnapshot.STATUS_RUNNING
    val cancelled = !running && (
        snapshot.status == SubagentActivitySnapshot.STATUS_CANCELLED ||
            (
                message.status == ToolActivityStatusUi.Unknown &&
                    snapshot.status != SubagentActivitySnapshot.STATUS_COMPLETED &&
                    snapshot.status != SubagentActivitySnapshot.STATUS_FAILED
                )
        )
    val failed = !running && !cancelled && (
        message.status == ToolActivityStatusUi.Failed ||
            snapshot.status == SubagentActivitySnapshot.STATUS_FAILED
        )
    val typeLabel = snapshot.subagentType.ifBlank { "subagent" }
    val taskTitle = snapshot.description.ifBlank { message.argumentsSummary.ifBlank { "Subagent" } }
    val subtitle = when {
        message.status == ToolActivityStatusUi.Unknown -> "运行已中断；这是最后保留状态"
        running -> snapshot.currentAction.takeIf { it.isNotBlank() }
            ?: if (snapshot.toolStepCount > 0) {
                stringResource(R.string.subagent_steps_executed, snapshot.toolStepCount)
            } else {
                stringResource(R.string.subagent_status_running)
            }
        cancelled -> if (snapshot.toolStepCount > 0) {
            stringResource(R.string.subagent_stopped_with_tools, snapshot.toolStepCount)
        } else {
            stringResource(R.string.subagent_status_stopped)
        }
        failed -> snapshot.error?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: message.outcomeLine()
            ?: stringResource(R.string.subagent_status_incomplete)
        else -> when {
            snapshot.toolStepCount > 0 -> stringResource(R.string.subagent_completed_with_tools, snapshot.toolStepCount)
            else -> message.outcomeLine() ?: stringResource(R.string.subagent_status_completed)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showInspector = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubagentTypeBadge(type = typeLabel, active = running)
                    Spacer(modifier = Modifier.width(6.dp))
                    ShimmerText(
                        text = taskTitle,
                        active = running,
                        style = MiuixTheme.textStyles.body2,
                        color = if (running) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failed && !running) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (elapsedSeconds != null && elapsedSeconds >= 3) {
                Text(
                    text = stringResource(R.string.tool_elapsed_seconds, elapsedSeconds),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.subagent_view_details),
                modifier = Modifier
                    .padding(start = 6.dp, end = 8.dp)
                    .size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            Dialog(
                visible = showInspector && !showFullDetails,
                onDismissRequest = { showInspector = false },
                dark = dark,
                title = taskTitle,
                confirmAction = CouiDialogAction(
                    text = stringResource(R.string.action_close),
                    onClick = { showInspector = false },
                ),
                placement = CouiDialogPlacement.Bottom,
                material = rememberCouiDialogMaterial(dark),
                backdrop = rememberCouiWindowBackdrop(),
            ) {
                SubagentInspectorContent(
                    messageId = message.id,
                    snapshot = snapshot,
                    running = running,
                    cancelled = cancelled,
                    failed = failed,
                    onOpenDetails = { showFullDetails = true },
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            GlassBottomSheet(
                show = showInspector && !showFullDetails,
                backdrop = currentGlassOverlayBackdrop(),
                title = taskTitle,
                onDismissRequest = { showInspector = false },
            ) {
                SubagentInspectorContent(
                    messageId = message.id,
                    snapshot = snapshot,
                    running = running,
                    cancelled = cancelled,
                    failed = failed,
                    onOpenDetails = { showFullDetails = true },
                )
            }
        }
    }
    OverlayBottomSheet(
        show = showFullDetails,
        title = "历史记录",
        onDismissRequest = { showFullDetails = false },
    ) {
        if (showFullDetails) SubagentFullDetails(snapshot.detailId, allowDelete = !running)
    }
}

@Composable
private fun SubagentTypeBadge(
    type: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (active) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = modifier
            .squircleSurface(color = containerColor, cornerRadius = 6.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = type,
            style = MiuixTheme.textStyles.footnote2.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SubagentInspectorContent(
    messageId: String,
    snapshot: SubagentActivitySnapshot,
    running: Boolean,
    cancelled: Boolean,
    failed: Boolean,
    onOpenDetails: () -> Unit,
    stepsState: LazyListState = rememberLazyListState(),
) {
    val density = LocalDensity.current
    val maxSheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.76f
    var showInformation by rememberSaveable(messageId) { mutableStateOf(false) }
    val pulse = rememberDrawPhasePulse(active = running)
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val requestStats = rememberSubagentSettingsSummary(snapshot.subagentId)
    val stepKeys = remember(snapshot.steps) {
        val occurrences = mutableMapOf<String, Int>()
        snapshot.steps.map { step ->
            val occurrence = occurrences.getOrDefault(step.id, 0)
            occurrences[step.id] = occurrence + 1
            "step:${step.id.length}:${step.id}:$occurrence"
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCoui) {
                    Modifier.padding(horizontal = CouiDialogDefaults.contentPadding, vertical = 8.dp)
                } else {
                    Modifier
                        .heightIn(max = maxSheetHeight)
                        .navigationBarsPadding()
                },
            ),
    ) {
        // 官方状态灯与类型徽章保留；实际模型和最近上下文作为个人增量展示。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = pulse?.value ?: 1f }
                    .clip(CircleShape)
                    .background(
                        when {
                            running -> MiuixTheme.colorScheme.primary
                            failed -> StatusError
                            cancelled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
                            else -> StatusSuccess
                        },
                    ),
            )
            SubagentTypeBadge(type = snapshot.subagentType.ifBlank { "subagent" }, active = running)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = SubagentReadableText.modelName(snapshot.model),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = SubagentReadableText.contextSummary(snapshot),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            IconButton(onClick = { showInformation = !showInformation }) {
                Icon(
                    imageVector = if (showInformation) Icons.Rounded.Close else Icons.Rounded.MoreHoriz,
                    contentDescription = if (showInformation) "收起任务信息" else "查看任务指令与统计说明",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        // This entry remains visible for running, failed and cancelled tasks, including old
        // snapshots with no detail ID. The destination explains unavailable records instead.
        if (isCoui) {
            CouiText(text = requestStats, kind = CouiTextKind.ItemSummary)
            CouiPreference(title = "历史记录",
                summary = if (snapshot.historyIncomplete) "部分记录保存失败，历史可能不完整" else null, onClick = onOpenDetails)
        } else {
            Text(requestStats, style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            ArrowPreference(title = "历史记录",
                summary = if (snapshot.historyIncomplete) "部分记录保存失败，历史可能不完整" else null, onClick = onOpenDetails)
        }
        AnimatedVisibility(visible = showInformation) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight * 0.35f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("任务信息", style = MiuixTheme.textStyles.footnote2)
                Text(
                    text = SubagentReadableText.contextExplanation(snapshot),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (snapshot.previewTruncated) Text(
                    text = "当前为截短预览，不是模型收到的完整指令。",
                    style = MiuixTheme.textStyles.footnote2,
                )
                if (snapshot.prompt.isNotBlank()) {
                    Text(if (snapshot.resumed) "续接任务指令" else "任务指令", style = MiuixTheme.textStyles.footnote2)
                    SelectionContainer {
                        Text(snapshot.prompt, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
        LazyColumn(
            state = stepsState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            if (snapshot.omittedSteps > 0) {
                item(key = "omitted-steps") {
                    Text(
                        text = stringResource(R.string.subagent_omitted_steps, snapshot.omittedSteps),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            // Reuse the main chat timeline, composing only visible execution steps.
            itemsIndexed(snapshot.steps, key = { index, _ -> stepKeys[index] }) { index, step ->
                val isLastStep = index == snapshot.steps.lastIndex && !running
                WorkTimelineNode(
                    icon = if (step.kind == SubagentStepSnapshot.KIND_THINKING) {
                        Icons.Rounded.Lightbulb
                    } else {
                        iconForTool(step.toolName)
                    },
                    tone = when (step.status) {
                        SubagentStepSnapshot.STATUS_RUNNING -> WorkNodeTone.Running
                        SubagentStepSnapshot.STATUS_FAILED -> WorkNodeTone.Failed
                        else -> WorkNodeTone.Done
                    },
                    isLast = isLastStep,
                    connectTop = index > 0,
                ) {
                    SubagentStepRow(step = step)
                }
            }
            if (running && snapshot.steps.none { it.status == SubagentStepSnapshot.STATUS_RUNNING }) {
                item(key = "current-action") {
                    WorkTimelineNode(
                        icon = Icons.Rounded.Lightbulb,
                        tone = WorkNodeTone.Running,
                        isLast = true,
                        connectTop = snapshot.steps.isNotEmpty(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = WorkRowHeight)
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            ShimmerText(
                                text = snapshot.currentAction.ifBlank { stringResource(R.string.subagent_status_running) },
                                active = true,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            val error = snapshot.error?.takeIf { it.isNotBlank() && failed }
                ?: if (failed) stringResource(R.string.subagent_status_incomplete) else null
            if (error != null) {
                item(key = "error") {
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.footnote1,
                        color = StatusError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(color = StatusError.copy(alpha = 0.08f), cornerRadius = 12.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            if (cancelled) {
                item(key = "cancelled") {
                    Text(
                        text = snapshot.error?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subagent_status_stopped),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = 12.dp,
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            if (snapshot.output.isNotBlank()) {
                item(key = "output") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(
                                if (running) R.string.subagent_output_streaming_label else R.string.subagent_output_summary_label,
                            ),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        )
                        SelectionContainer {
                            Text(
                                text = snapshot.output,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .squircleSurface(
                                        color = MiuixTheme.colorScheme.surfaceContainer,
                                        cornerRadius = 12.dp,
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(16.dp)) }
        }

        // 轻量用量摘要：单行克制展示，不打断主时间线阅读
        snapshot.usage?.let { usage ->
            val usageParts = buildList {
                if (usage.inputTokens > 0) {
                    add(stringResource(R.string.subagent_usage_input, formatExactTokenCount(usage.inputTokens)))
                }
                if (usage.cachedTokens > 0) {
                    add(stringResource(R.string.subagent_usage_cached, formatExactTokenCount(usage.cachedTokens)))
                }
                if (usage.outputTokens > 0) {
                    add(stringResource(R.string.subagent_usage_output, formatExactTokenCount(usage.outputTokens)))
                }
                if (usage.llmMs > 0L) {
                    add(formatStatDuration(usage.llmMs))
                }
            }
            if (usageParts.isNotEmpty()) {
                Text(
                    text = usageParts.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun SubagentStepRow(
    step: SubagentStepSnapshot,
) {
    val hasDetail = step.detail.isNotBlank()
    var expanded by rememberSaveable(step.id) {
        mutableStateOf(false)
    }
    val running = step.status == SubagentStepSnapshot.STATUS_RUNNING
    val failed = step.status == SubagentStepSnapshot.STATUS_FAILED
    val readableTitle = remember(step.kind, step.title, step.detail, step.toolName) {
        if (step.kind == SubagentStepSnapshot.KIND_THINKING) {
            SubagentReadableText.thinkingTitle(step.detail)
        } else if (step.title.startsWith("终端") || step.title.isBlank()) {
            // Legacy detail can contain both the command and its result; never treat its
            // first line alone as the complete command of a compound execution.
            SubagentReadableText.toolTitle(step.toolName, step.title, step.detail)
        } else {
            step.title
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(8.dp))
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ShimmerText(
                    text = readableTitle,
                    active = running,
                    style = MiuixTheme.textStyles.body2,
                    color = when {
                        running -> MiuixTheme.colorScheme.onSurface
                        failed -> StatusError
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Text(
                    text = (if (step.kind == SubagentStepSnapshot.KIND_THINKING) "思考" else "工具执行") +
                        " · " + when { running -> "进行中"; failed -> "执行失败"; else -> "已结束" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (hasDetail) {
                ExpandChevron(
                    expanded = expanded,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && hasDetail,
            enter = ExpandDownEnter,
            exit = CollapseUpExit,
        ) {
            SelectionContainer {
                Text(
                    text = step.detail,
                    style = if (step.kind == SubagentStepSnapshot.KIND_TOOL) {
                        MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MiuixTheme.textStyles.footnote2
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f),
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .squircleSurface(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            cornerRadius = 10.dp,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
