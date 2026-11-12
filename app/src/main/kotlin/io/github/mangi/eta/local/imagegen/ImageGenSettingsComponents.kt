package io.github.mangi.eta.local.imagegen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Group
import com.itos.coui.layout.Footer
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Page-local adapters to the same official controls used by the approved v2 preview.
@Composable
internal fun ImageGenPage(title: String, onBack: () -> Unit, overlay: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiLazyScaffoldPage(title = title, onBack = onBack, overlay = overlay, content = content)
    else MiuixScaffoldPage(title = title, onBack = onBack, overlay = overlay, content = content)
}

@Composable
internal fun ImageGenGroup(title: String, content: @Composable () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Group(title = title) { content() }
    else {
        SmallTitle(title)
        Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { content() }
    }
}

@Composable
internal fun ImageGenRow(title: String, summary: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
    else BasicComponent(title = title, summary = summary, enabled = enabled, onClick = onClick)
}

@Composable
internal fun ImageGenNote(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Footer(text = text)
    else Text(text, style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
}

@Composable
internal fun ImageGenField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean = true, secret: Boolean = false) {
    val transformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiTextField(value = value, onValueChange = onValueChange, label = label, enabled = enabled,
            singleLine = true, visualTransformation = transformation, modifier = Modifier.fillMaxWidth())
    else TextField(value = value, onValueChange = onValueChange, label = label, enabled = enabled,
        singleLine = true, visualTransformation = transformation, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ImageGenAction(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
            role = if (primary) CouiButtonRole.Primary else CouiButtonRole.Secondary)
    else TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
internal fun ImageGenDropdown(title: String, items: List<String>, selected: Int, enabled: Boolean = true, onSelected: (Int) -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI)
        CouiDropdownPreference(title = title, items = items, selectedIndex = selected, enabled = enabled, onSelectedIndexChange = onSelected)
    else OverlayDropdownPreference(title = title, items = items, selectedIndex = selected, enabled = enabled, onSelectedIndexChange = onSelected)
}
