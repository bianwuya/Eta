package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ConversationRenameDialog(
    conversation: ConversationSummaryUi,
    onConfirmRename: (String) -> Unit,
    onRegenerateTitle: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = true,
                title = stringResource(R.string.conversation_rename_title),
                message = null,
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = renameInput.isNotBlank(),
                onConfirm = { onConfirmRename(renameInput) },
                onDismissRequest = onDismissRequest,
            ) {
                CouiTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                )
                CouiButton(
                    text = "重新生成标题",
                    enabled = !conversation.isActiveRun,
                    onClick = onRegenerateTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = stringResource(R.string.conversation_rename_title),
                onDismissRequest = onDismissRequest,
            ) {
                Column {
                    TextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = stringResource(R.string.conversation_rename_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        text = "重新生成标题",
                        enabled = !conversation.isActiveRun,
                        onClick = onRegenerateTitle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_save),
                        confirmEnabled = renameInput.isNotBlank(),
                        onCancel = onDismissRequest,
                        onConfirm = { onConfirmRename(renameInput) },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

