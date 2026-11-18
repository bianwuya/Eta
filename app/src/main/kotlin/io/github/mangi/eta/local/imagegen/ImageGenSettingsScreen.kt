package io.github.mangi.eta.local.imagegen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Serializable
private sealed interface ImageGenRoute : NavKey {
    @Serializable data object Connection : ImageGenRoute
    @Serializable data object Models : ImageGenRoute
}

@Composable
internal fun ImageGenSettingsScreen(
    onBack: () -> Unit,
    storeOverride: ImageGenConfigStore? = null,
    fetchModels: suspend (HttpUrl, String) -> List<ImageGenModelCandidate> = { url, key -> ImageGenModelCatalog.fetch(url, key) },
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, storeOverride) { storeOverride ?: ImageGenConfigStore(context) }
    val scope = rememberCoroutineScope()
    val stack = rememberNavBackStack<ImageGenRoute>(ImageGenRoute.Connection)
    var config by remember { mutableStateOf<ImageGenConfig?>(null) }
    var hasSavedKey by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") } // Deliberately not saveable.
    var working by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        // The restored route cannot restore the in-memory connection draft or key.
        if (stack.lastOrNull() == ImageGenRoute.Models) stack.removeAt(stack.lastIndex)
        try {
            config = withContext(Dispatchers.IO) { store.read() }
            endpoint = config?.endpoint.orEmpty()
            model = config?.model.orEmpty()
            hasSavedKey = withContext(Dispatchers.IO) { runCatching { store.connection() != null }.getOrDefault(false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = R.string.image_gen_read_failed
        } finally {
            working = false
        }
    }

    fun updateConnection(failure: Int, action: suspend () -> Unit) {
        if (working || stack.lastOrNull() != ImageGenRoute.Connection) return
        working = true
        status = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = failure
            } finally {
                working = false
            }
        }
    }

    val back: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }
    val swipeDismiss = NavSwipeDirection.LeftToRight.takeIf { LocalAppearanceSettings.current.swipeDismissEnabled }
    val target = remember(endpoint) { ImageGenEndpoint.resolve(endpoint) }
    val canReuseKey = hasSavedKey && target != null && target.toString() == config?.endpoint
    val draftKey = keyDraft.trim()
    val canSave = target != null && isImageGenModelId(model) &&
        (isImageGenApiKey(draftKey) || draftKey.isEmpty() && canReuseKey)

    NavDisplay(backStack = stack, onBack = back, effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius())) {
        entry<ImageGenRoute.Connection> {
            ImageGenPage(stringResource(R.string.image_gen_settings_title), back, overlay = {
                val remove: () -> Unit = {
                    confirmRemove = false
                    updateConnection(R.string.image_gen_remove_failed) {
                        withContext(Dispatchers.IO) { store.remove() }
                        config = null
                        hasSavedKey = false
                        endpoint = ""
                        model = ""
                        keyDraft = ""
                        status = R.string.image_gen_removed
                    }
                }
                if (confirmRemove) ImageGenRemoveDialog(onConfirm = remove, onDismiss = { confirmRemove = false })
            }) {
                item(key = "imagegen:connection:about") {
                    ImageGenNote(stringResource(R.string.image_gen_settings_summary))
                    ImageGenNote(stringResource(when {
                        working -> R.string.image_gen_config_working
                        hasSavedKey -> R.string.image_gen_config_active
                        else -> R.string.image_gen_config_inactive
                    }))
                }
                item(key = "imagegen:connection:endpoint") {
                    ImageGenField(stringResource(R.string.image_gen_endpoint), endpoint, { next ->
                        if (target != ImageGenEndpoint.resolve(next)) keyDraft = ""
                        endpoint = next
                    }, enabled = !working, keyboardType = KeyboardType.Uri)
                    ImageGenNote(stringResource(R.string.image_gen_endpoint_hint))
                }
                item(key = "imagegen:connection:key") {
                    ImageGenField(stringResource(R.string.image_gen_api_key), keyDraft, { keyDraft = it },
                        enabled = !working, keyboardType = KeyboardType.Password)
                    if (canReuseKey) ImageGenNote(stringResource(R.string.image_gen_key_saved))
                }
                item(key = "imagegen:connection:model") {
                    EtaPreferenceGroupItem(isFirst = true, isLast = true) {
                        ImageGenRow(stringResource(R.string.image_gen_model), model.ifEmpty { stringResource(R.string.image_gen_picker_title) },
                            enabled = !working) {
                            if (!working && stack.lastOrNull() == ImageGenRoute.Connection) stack.add(ImageGenRoute.Models)
                        }
                    }
                }
                item(key = "imagegen:connection:save") {
                    status?.let { ImageGenNote(stringResource(it)) }
                    ImageGenAction(stringResource(R.string.image_gen_save), enabled = !working && canSave, primary = true) {
                        val savedTarget = target ?: return@ImageGenAction
                        val selected = model
                        if (!canSave) return@ImageGenAction
                        updateConnection(R.string.image_gen_save_failed) {
                            val saved = withContext(Dispatchers.IO) { store.save(savedTarget, selected, draftKey) }
                            config = saved
                            endpoint = saved.endpoint
                            hasSavedKey = true
                            keyDraft = ""
                            status = R.string.image_gen_saved
                        }
                    }
                    if (config != null) ImageGenAction(stringResource(R.string.image_gen_remove), enabled = !working) {
                        confirmRemove = true
                    }
                }
            }
        }
        entry<ImageGenRoute.Models>(swipeDismiss = swipeDismiss) {
            val active = stack.lastOrNull() == ImageGenRoute.Models
            var models by remember(target, draftKey) { mutableStateOf(emptyList<ImageGenModelCandidate>()) }
            var loading by remember(target, draftKey) { mutableStateOf(true) }
            var catalogStatus by remember(target, draftKey) { mutableStateOf<Int?>(null) }
            var request by remember { mutableIntStateOf(0) }
            LaunchedEffect(active, request, target, draftKey) {
                if (!active) return@LaunchedEffect
                loading = true
                catalogStatus = null
                try {
                    val key = if (draftKey.isNotEmpty()) draftKey else target?.let {
                        withContext(Dispatchers.IO) { store.savedKeyFor(it) }
                    }.orEmpty()
                    if (target == null || !isImageGenApiKey(key)) {
                        catalogStatus = R.string.image_gen_catalog_needs_connection
                    } else {
                        models = fetchModels(target, key)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    catalogStatus = R.string.image_gen_catalog_failed
                } finally {
                    loading = false
                }
            }
            ImageGenModelPickerScreen(models, model, loading, catalogStatus, back, onRefresh = {
                if (!loading && active) {
                    loading = true
                    catalogStatus = null
                    request++
                }
            }) { selected ->
                if (stack.lastOrNull() == ImageGenRoute.Models) {
                    model = selected
                    back()
                }
            }
        }
    }
}

@Composable
private fun ImageGenRemoveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.image_gen_remove)
    val message = stringResource(R.string.image_gen_remove_summary)
    val confirm = stringResource(R.string.image_gen_remove_confirm)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiConfirmDialog(visible = true, title = title, message = message, confirmText = confirm,
            onConfirm = onConfirm, onDismissRequest = onDismiss, destructive = true)
    } else {
        GlassDialog(show = true, backdrop = currentGlassOverlayBackdrop(), title = title, summary = message, onDismissRequest = onDismiss) {
            MiuixDialogActions(confirmText = confirm, onCancel = onDismiss, onConfirm = onConfirm, destructive = true)
        }
    }
}
