package io.github.mangi.eta.local.imagegen

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem

internal const val IMAGE_GEN_REFRESH_ROW_KEY = "imagegen:fixed:refresh"
internal fun imageGenModelRowKey(modelId: String): String = "imagegen:model:$modelId"

@Composable
internal fun ImageGenModelPickerScreen(
    models: List<ImageGenModelCandidate>,
    selectedId: String,
    loading: Boolean,
    status: Int?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    val filtered = remember(models, search) {
        models.distinctBy { it.modelId }.filter {
            it.modelId.contains(search.trim(), ignoreCase = true) || it.name.contains(search.trim(), ignoreCase = true)
        }
    }
    ImageGenPage(stringResource(R.string.image_gen_picker_title), onBack) {
        item(key = "imagegen:fixed:search") {
            ImageGenField(stringResource(R.string.image_gen_search), search, { search = it })
        }
        item(key = IMAGE_GEN_REFRESH_ROW_KEY) {
            ImageGenAction(stringResource(if (loading) R.string.image_gen_catalog_loading else R.string.image_gen_refresh),
                enabled = !loading, onClick = onRefresh)
        }
        if (status != null || (!loading && filtered.isEmpty())) {
            item(key = "imagegen:fixed:status") {
                ImageGenNote(stringResource(status ?: if (models.isEmpty()) R.string.image_gen_catalog_empty else R.string.image_gen_no_match))
            }
        }
        itemsIndexed(filtered, key = { _, model -> imageGenModelRowKey(model.modelId) }) { index, model ->
            val isSelected = model.modelId == selectedId
            EtaPreferenceGroupItem(isFirst = index == 0, isLast = index == filtered.lastIndex) {
                ImageGenRow(
                    model.modelId,
                    if (isSelected) stringResource(R.string.image_gen_model_selected) else model.name.takeIf { it != model.modelId },
                    modifier = Modifier.semantics { selected = isSelected },
                    onClick = { onSelected(model.modelId) },
                )
            }
        }
        item(key = "imagegen:fixed:manual") {
            ImageGenField(stringResource(R.string.image_gen_manual), manual, { manual = it })
            val id = manual.trim()
            ImageGenAction(stringResource(R.string.image_gen_use_manual), enabled = isImageGenModelId(id)) {
                onSelected(id)
            }
        }
    }
}
