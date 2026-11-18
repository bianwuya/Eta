package io.github.mangi.eta.local.imagegen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Footer
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ImageGenPage(
    title: String,
    onBack: () -> Unit,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiLazyScaffoldPage(title = title, onBack = onBack, overlay = overlay, content = content)
    } else {
        MiuixScaffoldPage(title = title, onBack = onBack, modifier = Modifier.imePadding(), overlay = overlay, content = content)
    }
}

@Composable
internal fun ImageGenRow(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPreference(title = title, summary = summary, modifier = modifier, enabled = enabled, onClick = onClick)
    } else {
        BasicComponent(title = title, summary = summary, modifier = modifier, enabled = enabled, onClick = onClick)
    }
}

@Composable
internal fun ImageGenNote(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        Footer(text = text)
    } else {
        Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
    }
}

@Composable
internal fun ImageGenField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val transformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    val keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiTextField(value = value, onValueChange = onValueChange, label = label, enabled = enabled,
            visualTransformation = transformation, modifier = modifier, keyboardOptions = keyboardOptions)
    } else {
        TextField(value = value, onValueChange = onValueChange, label = label, enabled = enabled,
            singleLine = true, visualTransformation = transformation, modifier = modifier, keyboardOptions = keyboardOptions)
    }
}

@Composable
internal fun ImageGenAction(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
            role = if (primary) CouiButtonRole.Primary else CouiButtonRole.Secondary)
    } else {
        TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
            colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
    }
}
