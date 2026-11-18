package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.EtaPortableBackup
import io.github.mangi.eta.data.repository.EtaPortableBackupArchive
import io.github.mangi.eta.data.repository.EtaPortableConflict
import io.github.mangi.eta.data.repository.EtaPortableIncompleteRestore
import io.github.mangi.eta.data.repository.EtaPortableManifest
import io.github.mangi.eta.data.repository.EtaPortablePart
import io.github.mangi.eta.data.repository.EtaPortablePasswordRequired
import io.github.mangi.eta.data.repository.EtaPortableRuntimeBusy
import io.github.mangi.eta.data.repository.EtaPortableSizeLimit
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.couiPrimaryDialogAction
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

internal enum class PortableBackupPhase {
    IDLE, PICK_EXPORT, PICK_IMPORT, EXPORT_PASSWORD, IMPORT_PASSWORD, CONFIRM_RESTORE,
    INSPECTING, EXPORTING, PREPARING_RESTORE, APPLYING_RESTORE;

    val locked: Boolean
        get() = this in setOf(INSPECTING, EXPORTING, PREPARING_RESTORE, APPLYING_RESTORE)
    val dismissible: Boolean
        get() = this in setOf(EXPORT_PASSWORD, IMPORT_PASSWORD, CONFIRM_RESTORE)
}

internal class PortableBackupControlsState {
    var phase by mutableStateOf(PortableBackupPhase.IDLE)
    var staged by mutableStateOf<EtaPortableBackup.Staged?>(null)
    var exportParts by mutableStateOf(EtaPortableBackup.allParts)
    var restoreParts by mutableStateOf(emptySet<EtaPortablePart>())
    var password by mutableStateOf("")
    var uri: Uri? = null
    var pendingExport = emptySet<EtaPortablePart>()
    lateinit var export: (Set<EtaPortablePart>) -> Unit
    lateinit var pickImport: () -> Unit
    lateinit var confirm: () -> Unit

    fun discard() {
        staged?.close()
        staged = null
        restoreParts = emptySet()
    }

    fun dismissDialog() {
        if (!phase.dismissible) return
        password = ""
        uri = null
        phase = PortableBackupPhase.IDLE
    }
}

@Composable
internal fun rememberEtaPortableBackupControls(
    context: Context,
    onExport: suspend (Set<EtaPortablePart>, String?, () -> OutputStream) -> EtaPortableManifest,
    onImport: suspend (EtaPortableBackup.Staged, Set<EtaPortablePart>, suspend () -> Unit) -> EtaPortableBackup.RestoreResult,
): PortableBackupControlsState {
    val scope = rememberCoroutineScope()
    val controls = remember { PortableBackupControlsState() }
    DisposableEffect(controls) {
        onDispose { controls.discard(); controls.password = "" }
    }
    BackHandler(enabled = controls.phase.locked) { /* No NavDisplay back while a backup owns the files. */ }

    fun toast(@StringRes id: Int, vararg args: Any) {
        Toast.makeText(context, context.getString(id, *args), Toast.LENGTH_LONG).show()
    }
    fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        // Raw serialization/crypto errors can include backup contents. Only display fixed messages.
        when (failure) {
            is EtaPortableSizeLimit -> toast(R.string.portable_backup_too_large)
            is EtaPortableRuntimeBusy -> toast(R.string.portable_backup_runtime_busy)
            is EtaPortableConflict -> toast(R.string.portable_backup_conflict)
            is EtaPortableIncompleteRestore -> toast(if (failure.committed) R.string.portable_backup_committed_warning
                else R.string.portable_backup_rollback_warning)
            is AEADBadTagException -> toast(R.string.portable_backup_bad_password)
            else -> toast(R.string.portable_backup_failed)
        }
    }

    fun inspect(uri: Uri, password: String?) {
        controls.phase = PortableBackupPhase.INSPECTING
        scope.launch {
            try {
                val staged = EtaPortableBackup.inspect(context, password) {
                    context.contentResolver.openInputStream(uri) ?: error("Document cannot be opened")
                }
                controls.staged = staged
                controls.restoreParts = emptySet()
                controls.uri = null
                controls.phase = PortableBackupPhase.IDLE
            } catch (_: EtaPortablePasswordRequired) {
                controls.phase = PortableBackupPhase.IMPORT_PASSWORD
            } catch (failure: Exception) {
                fail(failure)
                if (failure is AEADBadTagException) controls.phase = PortableBackupPhase.IMPORT_PASSWORD
                else {
                    controls.uri = null
                    controls.phase = PortableBackupPhase.IDLE
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (controls.phase != PortableBackupPhase.PICK_EXPORT) return@rememberLauncherForActivityResult
        val password = controls.password.takeUnless(String::isBlank)
        controls.password = ""
        if (uri == null) {
            controls.phase = PortableBackupPhase.IDLE
            return@rememberLauncherForActivityResult
        }
        controls.phase = PortableBackupPhase.EXPORTING
        val parts = controls.pendingExport
        scope.launch {
            try {
                val result = onExport(parts, password) {
                    context.contentResolver.openOutputStream(uri, "wt") ?: error("Document cannot be opened")
                }
                toast(R.string.portable_backup_exported, result.parts.size)
            } catch (failure: Exception) {
                fail(failure)
            } finally {
                controls.phase = PortableBackupPhase.IDLE
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (controls.phase != PortableBackupPhase.PICK_IMPORT) return@rememberLauncherForActivityResult
        controls.uri = uri
        if (uri == null) controls.phase = PortableBackupPhase.IDLE else inspect(uri, null)
    }

    fun pickExport() {
        val name = if (controls.pendingExport == EtaPortableBackup.allParts) "Full" else "Selected"
        val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        controls.phase = PortableBackupPhase.PICK_EXPORT
        try {
            exportLauncher.launch("Eta-$name-$date.etabackup")
        } catch (failure: Exception) {
            controls.password = ""
            controls.phase = PortableBackupPhase.IDLE
            fail(failure)
        }
    }
    controls.export = { parts ->
        if (controls.phase == PortableBackupPhase.IDLE && controls.staged == null && parts.isNotEmpty()) {
            controls.pendingExport = parts
            controls.password = ""
            if (parts.any { it.requiresPassword }) controls.phase = PortableBackupPhase.EXPORT_PASSWORD else pickExport()
        }
    }
    controls.pickImport = {
        if (controls.phase == PortableBackupPhase.IDLE && controls.staged == null) {
            controls.phase = PortableBackupPhase.PICK_IMPORT
            try {
                importLauncher.launch(arrayOf("*/*"))
            } catch (failure: Exception) {
                controls.phase = PortableBackupPhase.IDLE
                fail(failure)
            }
        }
    }
    controls.confirm = confirm@{
        when (controls.phase) {
            PortableBackupPhase.EXPORT_PASSWORD -> if (controls.password.isNotBlank() && controls.password.length >= 8) pickExport()
            PortableBackupPhase.IMPORT_PASSWORD -> {
                val uri = controls.uri ?: return@confirm
                val password = controls.password.takeUnless(String::isBlank) ?: return@confirm
                controls.password = ""
                inspect(uri, password)
            }
            PortableBackupPhase.CONFIRM_RESTORE -> {
                val candidate = controls.staged ?: return@confirm
                val parts = controls.restoreParts.intersect(candidate.manifest.parts)
                if (parts.isEmpty()) return@confirm
                controls.staged = null // Transfer ownership to the job; disposal must not delete an active restore's files.
                controls.phase = PortableBackupPhase.PREPARING_RESTORE
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var keep = false
                    try {
                        val result = onImport(candidate, parts) {
                            withContext(Dispatchers.Main.immediate) { controls.phase = PortableBackupPhase.APPLYING_RESTORE }
                        }
                        toast(if (result.imageSkipped) R.string.portable_backup_image_skipped else R.string.portable_backup_restored)
                    } catch (failure: Exception) {
                        fail(failure)
                        currentCoroutineContext().ensureActive()
                        if (failure !is EtaPortableIncompleteRestore) {
                            controls.staged = candidate
                            keep = true
                        }
                    } finally {
                        if (!keep) { candidate.close(); controls.restoreParts = emptySet() }
                        controls.phase = PortableBackupPhase.IDLE
                    }
                }
            }
            else -> Unit
        }
    }
    return controls
}

@Composable
internal fun EtaPortableBackupControls(controls: PortableBackupControlsState) {
    val staged = controls.staged
    val available = staged?.manifest?.parts ?: EtaPortableBackup.allParts
    val selected = if (staged == null) controls.exportParts else controls.restoreParts
    val ready = controls.phase == PortableBackupPhase.IDLE
    val title = stringResource(if (staged == null) R.string.portable_backup_export_parts else R.string.portable_backup_restore_parts)
    val imageNote = stringResource(R.string.portable_backup_image_note)
    val rows = if (staged == null) listOf(
        BackupRow(stringResource(R.string.portable_backup_export_all), stringResource(R.string.portable_backup_export_all_note), ready) {
            controls.export(EtaPortableBackup.allParts)
        },
        BackupRow(stringResource(R.string.portable_backup_export_selected), stringResource(R.string.portable_backup_export_note,
            EtaPortableBackupArchive.MAX_LOCKED_MIB), ready && selected.isNotEmpty()) { controls.export(selected) },
        BackupRow(stringResource(R.string.data_backup_import), stringResource(R.string.portable_backup_import_note), ready, controls.pickImport),
    ) else listOf(
        BackupRow(stringResource(R.string.portable_backup_restore), stringResource(if (staged.isLegacyJson)
            R.string.portable_backup_legacy else R.string.portable_backup_verified), ready && selected.isNotEmpty()) {
            if (controls.phase == PortableBackupPhase.IDLE) controls.phase = PortableBackupPhase.CONFIRM_RESTORE
        },
        BackupRow(stringResource(R.string.portable_backup_discard), null, ready) { if (controls.phase == PortableBackupPhase.IDLE) controls.discard() },
    )
    fun toggle(part: EtaPortablePart, enabled: Boolean) {
        if (controls.phase != PortableBackupPhase.IDLE) return
        val parts = if (staged == null) controls.exportParts else controls.restoreParts
        val next = if (enabled) parts + part else parts - part
        if (staged == null) controls.exportParts = next else controls.restoreParts = next
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group(title = stringResource(R.string.data_backup_actions)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) Divider()
                    CouiPreference(title = row.title, summary = row.summary, enabled = row.enabled, onClick = row.click)
                }
            }
            Group(title = title) {
                for (part in available.sortedBy(EtaPortablePart::ordinal)) {
                    CouiSwitchPreference(title = stringResource(part.label()), checked = part in selected, enabled = ready,
                        summary = imageNote.takeIf { part == EtaPortablePart.IMAGEGEN }, onCheckedChange = { toggle(part, it) })
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Column {
                SmallTitle(stringResource(R.string.data_backup_actions))
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    rows.forEach { row -> ArrowPreference(title = row.title, summary = row.summary, enabled = row.enabled, onClick = row.click) }
                }
                SmallTitle(title)
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    for (part in available.sortedBy(EtaPortablePart::ordinal)) {
                        SwitchPreference(title = stringResource(part.label()), checked = part in selected, enabled = ready,
                            summary = imageNote.takeIf { part == EtaPortablePart.IMAGEGEN }, onCheckedChange = { toggle(part, it) })
                    }
                }
            }
        }
    }
}

private data class BackupRow(val title: String, val summary: String?, val enabled: Boolean, val click: () -> Unit)

@Composable
internal fun EtaPortableBackupDialog(controls: PortableBackupControlsState) {
    val phase = controls.phase
    if (!phase.dismissible && !phase.locked) return
    val exportPassword = phase == PortableBackupPhase.EXPORT_PASSWORD
    val passwordPrompt = exportPassword || phase == PortableBackupPhase.IMPORT_PASSWORD
    val review = phase == PortableBackupPhase.CONFIRM_RESTORE
    val title = stringResource(when (phase) {
        PortableBackupPhase.EXPORT_PASSWORD -> R.string.portable_backup_set_password
        PortableBackupPhase.IMPORT_PASSWORD -> R.string.portable_backup_enter_password
        PortableBackupPhase.CONFIRM_RESTORE -> R.string.portable_backup_confirm
        PortableBackupPhase.INSPECTING -> R.string.portable_backup_inspecting
        PortableBackupPhase.EXPORTING -> R.string.portable_backup_exporting
        PortableBackupPhase.PREPARING_RESTORE -> R.string.portable_backup_preparing
        else -> R.string.portable_backup_applying
    })
    val reviewLabels = mutableListOf<String>()
    if (review) for (part in controls.restoreParts.sortedBy(EtaPortablePart::ordinal)) reviewLabels += stringResource(part.label())
    val message = when {
        exportPassword -> stringResource(R.string.portable_backup_password_note)
        review -> stringResource(R.string.portable_backup_confirm_note, reviewLabels.joinToString())
        phase.locked -> stringResource(R.string.portable_backup_wait)
        else -> null
    }
    val confirmText = stringResource(if (review) R.string.portable_backup_restore else R.string.action_continue)
    val confirmEnabled = when {
        review -> controls.restoreParts.isNotEmpty()
        passwordPrompt -> controls.password.isNotBlank() && (!exportPassword || controls.password.length >= 8)
        else -> false
    }
    val destructive = review && EtaPortablePart.DATA in controls.restoreParts
    val label = stringResource(R.string.portable_backup_password)
    val changePassword: (String) -> Unit = { if (controls.phase.dismissible && it.length <= 256) controls.password = it }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            Dialog(
                visible = true,
                title = title,
                message = message,
                dark = dark,
                placement = CouiDialogPlacement.Center,
                properties = DialogProperties(dismissOnBackPress = phase.dismissible, dismissOnClickOutside = phase.dismissible),
                material = rememberCouiDialogMaterial(dark),
                backdrop = rememberCouiWindowBackdrop(),
                onDismissRequest = controls::dismissDialog,
                confirmAction = couiPrimaryDialogAction(confirmText, controls.confirm, confirmEnabled, destructive, dark),
                dismissAction = CouiDialogAction(stringResource(R.string.action_cancel), controls::dismissDialog, enabled = phase.dismissible),
            ) {
                if (passwordPrompt) CouiTextField(value = controls.password, onValueChange = changePassword, label = label,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), showClearButton = false,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (phase.locked) InfiniteProgressIndicator(modifier = Modifier.padding(16.dp), size = 24.dp)
            }
        }
        AppearanceUiStyle.MIUIX -> GlassDialog(
            show = true,
            backdrop = currentGlassOverlayBackdrop(),
            title = title,
            summary = message,
            onDismissRequest = controls::dismissDialog, // Shared guard for system Back and dim-area clicks.
        ) {
            if (passwordPrompt) TextField(value = controls.password, onValueChange = changePassword, label = label,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (phase.locked) InfiniteProgressIndicator(modifier = Modifier.padding(bottom = 12.dp), size = 24.dp)
            MiuixDialogActions(confirmText = confirmText, confirmEnabled = confirmEnabled, cancelEnabled = phase.dismissible,
                destructive = destructive, onCancel = controls::dismissDialog, onConfirm = controls.confirm)
        }
    }
}

@StringRes
private fun EtaPortablePart.label(): Int = when (this) {
    EtaPortablePart.DATA -> R.string.portable_backup_part_data
    EtaPortablePart.SKILLS -> R.string.portable_backup_part_skills
    EtaPortablePart.SUBAGENTS -> R.string.portable_backup_part_subagents
    EtaPortablePart.SETTINGS -> R.string.portable_backup_part_settings
    EtaPortablePart.IMAGEGEN -> R.string.portable_backup_part_image
    EtaPortablePart.MCP -> R.string.portable_backup_part_mcp
}
