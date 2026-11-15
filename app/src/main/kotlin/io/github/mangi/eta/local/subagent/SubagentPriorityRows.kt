package io.github.mangi.eta.local.subagent

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiPreference
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text

/** A gesture on official preference rows. The caller remains the only settings writer. */
@Composable
internal fun SubagentPriorityRows(models: List<Model>, priority: List<String>, enabled: Boolean = true, onSave: (List<String>) -> Unit) {
    val source = remember(models, priority) {
        val byId = models.associateBy { it.id }
        priority.mapNotNull(byId::get).distinctBy { it.id } + models.filter { it.id !in priority }
    }
    var order by remember(source) { mutableStateOf(source) }
    var dragged by remember(source) { mutableStateOf<String?>(null) }
    var offset by remember(source) { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    val latestSave by rememberUpdatedState(onSave)
    fun save() { latestSave(order.map { it.id } + priority.filter { id -> models.none { it.id == id } }) }
    fun move(id: String, direction: Int): Boolean {
        val at = order.indexOfFirst { it.id == id }
        val target = at + direction
        if (!enabled || at < 0 || target !in order.indices) return false
        order = order.toMutableList().apply { add(target, removeAt(at)) }
        return true
    }
    order.forEachIndexed { index, model ->
        key(model.id) {
            val handle = Modifier.size(48.dp).semantics {
                contentDescription = "拖动调整 ${model.displayName} 的优先级"
                stateDescription = "第 ${index + 1} 优先级"
                if (enabled) customActions = listOf(CustomAccessibilityAction("上移") {
                    move(model.id, -1).also { if (it) save() }
                }, CustomAccessibilityAction("下移") {
                    move(model.id, 1).also { if (it) save() }
                })
            }.pointerInput(model.id, enabled, source) {
                if (enabled) detectDragGesturesAfterLongPress(
                    onDragStart = { dragged = model.id; offset = 0f },
                    onDragEnd = { dragged = null; offset = 0f; save() },
                    onDragCancel = { dragged = null; offset = 0f; order = source },
                ) { change, delta ->
                    change.consume(); offset += delta.y
                    val at = order.indexOfFirst { it.id == model.id }
                    val direction = if (offset > 0) 1 else -1
                    val neighbor = order.getOrNull(at + direction)
                    val distance = neighbor?.let { heights[it.id]?.toFloat() }
                    if (distance != null && kotlin.math.abs(offset) > distance / 2 && move(model.id, direction))
                        offset -= direction * distance
                }
            }
            val trailing: @Composable () -> Unit = {
                Box(handle, contentAlignment = Alignment.Center) {
                    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiText(text = "≡", kind = CouiTextKind.ItemTitle)
                    else Text("≡")
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .onSizeChanged { heights[model.id] = it.height }
                .zIndex(if (dragged == model.id) 1f else 0f)
                .graphicsLayer { translationY = if (dragged == model.id) offset else 0f }) {
                val summary = if (index == 0) "优先使用" else "备用模型"
                if (LocalUiStyle.current == AppearanceUiStyle.COUI)
                    CouiPreference(title = "${index + 1}  ${model.displayName}", summary = summary,
                        enabled = enabled, showChevron = false, trailing = trailing)
                else BasicComponent(title = "${index + 1}  ${model.displayName}", summary = summary,
                    enabled = enabled, endActions = { trailing() })
            }
        }
    }
}
