package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.EtaPortableBackup
import io.github.mangi.eta.data.repository.EtaPortablePart
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/** Reuses the existing Eta settings rows in both supported UI styles. */
@Composable
internal fun EtaPortableBackupControls(context: Context,
    onRestoreBackup: suspend (EtaPortableBackup.Staged, Set<EtaPortablePart>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(EtaPortableBackup.allParts) }
    var pendingParts by remember { mutableStateOf(EtaPortableBackup.allParts) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importingUri by remember { mutableStateOf<Uri?>(null) }
    var staged by remember { mutableStateOf<EtaPortableBackup.Staged?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(staged) {
        val current = staged
        onDispose { current?.close() }
    }

    fun message(value: String) {
        Toast.makeText(context, value, Toast.LENGTH_LONG).show()
    }
    fun fail(error: Throwable) {
        if (error is CancellationException) throw error
        message(error.message ?: "备份操作失败")
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val parts = pendingParts.toSet()
        val key = password.takeIf { it.isNotBlank() }
        password = ""
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        EtaPortableBackup.export(context, output, parts, key)
                    } ?: error("无法创建备份文件")
                }
                message("分类备份已导出：${parts.size} 个分类")
            } catch (error: Throwable) {
                fail(error)
            } finally {
                busy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importingUri = uri
            importing = true
            password = ""
            showPassword = true
        }
    }

    fun chooseExport(parts: Set<EtaPortablePart>) {
        if (busy) return
        if (parts.isEmpty()) {
            message("至少选择一个备份分类")
            return
        }
        pendingParts = parts.toSet()
        importing = false
        password = ""
        if (parts.any { it in setOf(EtaPortablePart.DATA, EtaPortablePart.SETTINGS, EtaPortablePart.MCP) }) {
            showPassword = true
        } else {
            exportLauncher.launch(portableFileName(parts))
        }
    }

    fun acceptPassword() {
        val key = password.takeIf { it.isNotBlank() }
        showPassword = false
        if (!importing) {
            exportLauncher.launch(portableFileName(pendingParts))
            return
        }
        val uri = importingUri ?: return
        scope.launch {
            busy = true
            try {
                val checked = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        EtaPortableBackup.inspect(context, input, key)
                    } ?: error("无法读取分类备份")
                }
                staged?.close()
                staged = checked
                selected = checked.manifest.parts
                showRestoreConfirm = false
                importingUri = null
                importing = false
                message("校验通过，包含：${checked.manifest.parts.joinToString { label(it) }}。选择分类后点击恢复")
            } catch (error: Throwable) {
                fail(error)
                showPassword = true
            } finally {
                password = ""
                busy = false
            }
        }
    }

    val parts = EtaPortablePart.entries
    val canExport = !busy && selected.isNotEmpty()
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group(title = "分类备份与恢复") {
                CouiPreference(
                    title = "创建完整加密备份",
                    summary = "全部 6 个分类；不含 Codex 订阅",
                    enabled = !busy,
                    onClick = { chooseExport(EtaPortableBackup.allParts) },
                )
                Divider()
                CouiPreference(
                    title = "导出选中分类",
                    summary = "Skills 可单独打包分发；含对话、设置或 MCP 时需要密码",
                    enabled = canExport,
                    onClick = { chooseExport(selected) },
                )
                Divider()
                CouiPreference(
                    title = "导入分类备份",
                    summary = "先检查内容与校验值，再选择恢复",
                    enabled = !busy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                if (staged != null) {
                    Divider()
                    CouiPreference(
                        title = "恢复已校验备份",
                        summary = "${staged?.manifest?.entries?.size ?: 0} 个文件；请先勾选下面的恢复分类",
                        enabled = !busy && selected.intersect(staged!!.manifest.parts).isNotEmpty(),
                        onClick = { showRestoreConfirm = true },
                    )
                    Divider()
                    CouiPreference(
                        title = "放弃恢复",
                        summary = "仅清理临时备份校验文件",
                        enabled = !busy,
                        onClick = { staged?.close(); staged = null },
                    )
                }
            }
            Group(title = "选择备份或恢复的分类") {
                parts.forEach { part ->
                    CouiSwitchPreference(
                        title = label(part),
                        checked = part in selected,
                        onCheckedChange = { on -> selected = if (on) selected + part else selected - part },
                    )
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            SmallTitle("分类备份与恢复")
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = "创建完整加密备份",
                    summary = "全部 6 个分类；不含 Codex 订阅",
                    enabled = !busy,
                    onClick = { chooseExport(EtaPortableBackup.allParts) },
                )
                ArrowPreference(
                    title = "导出选中分类",
                    summary = "Skills 可单独打包分发；含对话、设置或 MCP 时需要密码",
                    enabled = canExport,
                    onClick = { chooseExport(selected) },
                )
                ArrowPreference(
                    title = "导入分类备份",
                    summary = "先校验，再按所选分类恢复",
                    enabled = !busy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                if (staged != null) {
                    ArrowPreference(
                        title = "恢复已校验备份",
                        summary = "${staged?.manifest?.entries?.size ?: 0} 个文件；先选择下方分类",
                        enabled = !busy && selected.intersect(staged!!.manifest.parts).isNotEmpty(),
                        onClick = { showRestoreConfirm = true },
                    )
                    ArrowPreference(
                        title = "放弃恢复",
                        summary = "清理此次导入的临时校验文件",
                        enabled = !busy,
                        onClick = { staged?.close(); staged = null },
                    )
                }
            }
            SmallTitle("选择备份或恢复的分类")
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                parts.forEach { part ->
                    SwitchPreference(
                        title = label(part),
                        checked = part in selected,
                        onCheckedChange = { on -> selected = if (on) selected + part else selected - part },
                    )
                }
            }
        }
    }

    if (showPassword) {
        val allowed = importing || password.length >= 8
        val title = if (importing) "输入备份密码（未加密文件可留空）" else "为完整备份设置密码"
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val dark = CouiTheme.isDark
                Dialog(
                    visible = true,
                    title = title,
                    dark = dark,
                    onDismissRequest = { showPassword = false; password = "" },
                    confirmAction = CouiDialogAction(
                        text = "继续",
                        onClick = ::acceptPassword,
                        enabled = allowed,
                    ),
                    dismissAction = CouiDialogAction(
                        text = "取消",
                        onClick = { showPassword = false; password = "" },
                    ),
                    placement = CouiDialogPlacement.Center,
                    material = rememberCouiDialogMaterial(dark),
                    backdrop = rememberCouiWindowBackdrop(),
                ) {
                    CouiTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "备份密码",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        showClearButton = false,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = title,
                    summary = "备份密码无法从文件找回；请妥善保管",
                    onDismissRequest = { showPassword = false; password = "" },
                ) {
                    Column {
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "备份密码",
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                        MiuixDialogActions(
                            confirmText = "继续",
                            confirmEnabled = allowed,
                            onCancel = { showPassword = false; password = "" },
                            onConfirm = ::acceptPassword,
                        )
                    }
                }
            }
        }
    }

    val candidate = staged
    if (candidate != null && showRestoreConfirm) {
        val available = candidate.manifest.parts.intersect(selected)
        val summary = "文件已通过 SHA-256 校验。包含：${candidate.manifest.parts.joinToString { label(it) }}。将恢复：${available.joinToString { label(it) }}。同名 Skills/子代理不会直接覆盖；数据类会替换现有对话。"
        val onDismiss = { showRestoreConfirm = false; Unit }
        val onRestore = {
            scope.launch {
                if (busy) return@launch
                busy = true
                try {
                    onRestoreBackup(candidate, available)
                    message("已恢复：${available.joinToString { label(it) }}")
                    candidate.close()
                    staged = null
                } catch (error: Throwable) {
                    fail(error)
                } finally {
                    showRestoreConfirm = false
                    busy = false
                }
            }
            Unit
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> io.github.mangi.eta.ui.components.coui.CouiConfirmDialog(
                visible = true,
                title = "确认恢复分类备份",
                message = summary,
                confirmText = "恢复选中分类",
                confirmEnabled = !busy && available.isNotEmpty(),
                cancelEnabled = !busy,
                destructive = EtaPortablePart.DATA in available,
                onConfirm = onRestore,
                onDismissRequest = onDismiss,
            )
            AppearanceUiStyle.MIUIX -> WindowDialog(
                show = true,
                title = "确认恢复分类备份",
                summary = summary,
                onDismissRequest = onDismiss,
            ) {
                MiuixDialogActions(
                    confirmText = "恢复选中分类",
                    confirmEnabled = !busy && available.isNotEmpty(),
                    destructive = EtaPortablePart.DATA in available,
                    onCancel = onDismiss,
                    onConfirm = onRestore,
                )
            }
        }
    }
}

private fun label(part: EtaPortablePart): String = when (part) {
    EtaPortablePart.DATA -> "对话·供应商·记忆·角色"
    EtaPortablePart.SKILLS -> "Skills 与启用状态"
    EtaPortablePart.SUBAGENTS -> "自定义子代理"
    EtaPortablePart.SETTINGS -> "应用设置·路由·语音"
    EtaPortablePart.IMAGEGEN -> "独立生图配置"
    EtaPortablePart.MCP -> "MCP 服务器与授权"
}

private fun portableFileName(parts: Set<EtaPortablePart>): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val name = if (parts == EtaPortableBackup.allParts) "Full" else parts.sortedBy { it.ordinal }.joinToString("-")
    return "Eta-$name-$stamp.etabackup"
}
