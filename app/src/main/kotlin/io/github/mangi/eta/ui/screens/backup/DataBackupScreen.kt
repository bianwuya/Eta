package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.EtaPortableBackup
import io.github.mangi.eta.data.repository.EtaPortableManifest
import io.github.mangi.eta.data.repository.EtaPortablePart
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import java.io.OutputStream
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (Set<EtaPortablePart>, String?, () -> OutputStream) -> EtaPortableManifest,
    onImport: suspend (EtaPortableBackup.Staged, Set<EtaPortablePart>, suspend () -> Unit) -> EtaPortableBackup.RestoreResult,
) {
    val controls = rememberEtaPortableBackupControls(context, onExport, onImport)
    val goBack = { if (controls.phase == PortableBackupPhase.IDLE) onBack() }
    val title = stringResource(R.string.data_backup_title)
    val warningTitle = stringResource(R.string.data_backup_warning_title)
    val warning = stringResource(R.string.portable_backup_warning)
    val overlay: @Composable () -> Unit = { EtaPortableBackupDialog(controls) }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiScaffoldPage(title = title, onBack = goBack, overlay = overlay) {
            Group { CouiPreference(title = warningTitle, summary = warning, showChevron = false) }
            EtaPortableBackupControls(controls)
            CouiPageBottomSpacer()
        }
        AppearanceUiStyle.MIUIX -> MiuixScaffoldPage(title = title, onBack = goBack, overlay = overlay) {
            item(key = "warning") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BasicComponent(title = warningTitle, summary = warning)
                }
            }
            item(key = "portable") { EtaPortableBackupControls(controls) }
        }
    }
}
