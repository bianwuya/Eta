package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon

/**
 * Image preview is allocated only while open.
 * A capped 1600px display copy is decoded here; chat thumbnails are sampled separately.
 * Pinch to zoom (1..5x), drag within image bounds, double tap to zoom/reset, tap to dismiss.
 */
@Composable
internal fun ImageViewerDialog(dataUrl: String?, onDismiss: () -> Unit) {
    if (dataUrl == null) return
    val bitmap = rememberDataUrlBitmap(dataUrl) ?: return
    var zoom by remember(dataUrl) { mutableFloatStateOf(1f) }
    var offset by remember(dataUrl) { mutableStateOf(Offset.Zero) }
    var viewport by remember(dataUrl) { mutableStateOf(IntSize.Zero) }

    fun boundedOffset(value: Offset, targetZoom: Float): Offset {
        if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fit = minOf(
            viewport.width.toFloat() / bitmap.width.coerceAtLeast(1),
            viewport.height.toFloat() / bitmap.height.coerceAtLeast(1),
        )
        val boundX = maxOf(0f, (bitmap.width * fit * targetZoom - viewport.width) / 2f)
        val boundY = maxOf(0f, (bitmap.height * fit * targetZoom - viewport.height) / 2f)
        return Offset(value.x.coerceIn(-boundX, boundX), value.y.coerceIn(-boundY, boundY))
    }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 5f)
        zoom = nextZoom
        offset = if (nextZoom <= 1f) Offset.Zero else boundedOffset(offset + panChange, nextZoom)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(transform)
                    .pointerInput(dataUrl) {
                        detectTapGestures(
                            onDoubleTap = { point ->
                                val next = if (zoom > 1.05f) 1f else 2.5f
                                zoom = next
                                offset = if (next == 1f) Offset.Zero else boundedOffset(
                                    Offset(
                                        (viewport.width / 2f - point.x) * (next - 1f),
                                        (viewport.height / 2f - point.y) * (next - 1f),
                                    ),
                                    next,
                                )
                            },
                            onTap = { onDismiss() },
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(android.R.string.cancel),
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
        }
    }
}
