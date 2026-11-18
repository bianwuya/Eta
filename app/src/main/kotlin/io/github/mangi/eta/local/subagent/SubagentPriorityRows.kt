package io.github.mangi.eta.local.subagent

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon

@Composable
internal fun SubagentPriorityRows(
    models: List<Model>,
    priority: List<String>,
    ordinaryModelId: String?,
    enabled: Boolean,
    onSave: (List<String>) -> Unit,
) {
    val modelsById = remember(models) { models.associateBy { it.id } }
    val source = remember(modelsById, priority, ordinaryModelId) {
        subagentPriorityOrder(modelsById.keys.toList(), priority, ordinaryModelId)
    }
    val canReorder = enabled && source.size > 1
    var order by remember(source, canReorder) { mutableStateOf(source) }
    var draggedId by remember(source, canReorder) { mutableStateOf<String?>(null) }
    var translation by remember(source, canReorder) { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    val latestSave by rememberUpdatedState(onSave)
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val moveUp = stringResource(R.string.subagent_priority_move_up)
    val moveDown = stringResource(R.string.subagent_priority_move_down)

    fun moveByAccessibility(id: String, direction: Int): Boolean {
        val index = source.indexOf(id)
        val target = index + direction
        if (!canReorder || draggedId != null || index < 0 || target !in source.indices) return false
        latestSave(source.toMutableList().apply { add(target, removeAt(index)) })
        return true
    }

    Column {
        order.forEachIndexed { index, id ->
            key(id) {
                val model = modelsById.getValue(id)
                val title = stringResource(R.string.subagent_priority_row, index + 1, model.displayName)
                val handle: @Composable () -> Unit = {
                    if (canReorder) {
                        val description = stringResource(R.string.subagent_priority_handle, model.displayName)
                        val position = stringResource(R.string.subagent_priority_position, index + 1)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = description
                                    stateDescription = position
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUp) { moveByAccessibility(id, -1) })
                                        if (index < order.lastIndex) {
                                            add(CustomAccessibilityAction(moveDown) { moveByAccessibility(id, 1) })
                                        }
                                    }
                                }
                                .pointerInput(id, source) {
                                    var drag: SubagentPriorityDrag? = null
                                    fun reset() {
                                        drag = null
                                        draggedId = null
                                        translation = 0f
                                        order = source
                                    }
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (draggedId == null) {
                                                drag = SubagentPriorityDrag(source, id)
                                                draggedId = id
                                            }
                                        },
                                        onDragEnd = {
                                            val result = drag?.finish()
                                            if (drag != null) reset()
                                            result?.let(latestSave)
                                        },
                                        onDragCancel = { if (drag != null) reset() },
                                    ) { change, delta ->
                                        drag?.let { active ->
                                            change.consume()
                                            active.dragBy(delta.y, heights)
                                            order = active.order
                                            translation = active.translationY
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (coui) CouiIcon(Icons.Rounded.DragHandle, contentDescription = null)
                            else Icon(Icons.Rounded.DragHandle, contentDescription = null)
                        }
                    }
                }
                val row = Modifier.fillMaxWidth()
                    .onSizeChanged { heights[id] = it.height }
                    .zIndex(if (draggedId == id) 1f else 0f)
                    .graphicsLayer { translationY = if (draggedId == id) translation else 0f }
                if (coui) {
                    CouiPreference(modifier = row, title = title, enabled = enabled, trailing = handle)
                } else {
                    BasicComponent(modifier = row, title = title, enabled = enabled, endActions = { handle() })
                }
            }
        }
    }
}
