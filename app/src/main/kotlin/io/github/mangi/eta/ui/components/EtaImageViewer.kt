package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Compatibility entry for standalone previews outside ChatImagePreviewHost. Gestures are shared with upstream. */
@Composable
internal fun ImageViewerDialog(dataUrl: String?, onDismiss: () -> Unit) {
    if (dataUrl == null) return
    val bitmap = rememberDataUrlBitmap(dataUrl) ?: return
    ImageViewerDialog(bitmap, onDismiss)
}

@Composable
internal fun ImageViewerDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            HeroZoomableImageCanvas(bitmap = bitmap, progress = { 1f }, closing = false, sourceRect = null,
                sourceCornerPx = 0f, isActivePage = true, onZoomedChange = {}, onDismiss = onDismiss)
            Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(android.R.string.cancel),
                    modifier = Modifier.size(20.dp), tint = Color.White)
            }
        }
    }
}
