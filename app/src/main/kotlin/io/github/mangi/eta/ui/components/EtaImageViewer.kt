package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.positionChanged
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
    var transform by remember(dataUrl) { mutableStateOf(ImageViewerTransform()) }
    var viewport by remember(dataUrl) { mutableStateOf(IntSize.Zero) }
    val bitmapSize = IntSize(bitmap.width, bitmap.height)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .onSizeChanged { viewport = it }
                // Gestures belong to the untransformed parent: 1px of finger travel
                // always corresponds to 1px of visual translation, regardless of zoom.
                .pointerInput(dataUrl) {
                    // detectTransformGestures discards movement until touch slop is
                    // crossed. That leaves the grabbed bitmap pixel several pixels
                    // behind the finger for the rest of the drag. Read each sample
                    // directly when zoomed, while still filtering accidental taps
                    // and one-finger swipes at the unzoomed 1x scale.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var unzoomedMovement = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val pointersDown = event.changes.count { it.pressed }
                            val canTransform = transform.zoom > 1f || pointersDown > 1

                            if (pointersDown > 0 && canTransform &&
                                centroid != Offset.Unspecified &&
                                (pan != Offset.Zero || zoomChange != 1f)
                            ) {
                                transform = ImageViewerGestureMath.onGesture(
                                    current = transform,
                                    centroid = centroid,
                                    pan = pan,
                                    zoomChange = zoomChange,
                                    viewport = viewport,
                                    bitmap = bitmapSize,
                                )
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            } else if (pointersDown == 1 && !canTransform) {
                                unzoomedMovement += pan
                                // At 1x the image cannot pan. Still consume large
                                // swipes so they do not become a tap-to-dismiss.
                                if (unzoomedMovement.getDistance() > viewConfiguration.touchSlop) {
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(dataUrl) {
                    detectTapGestures(
                        onDoubleTap = { point ->
                            transform = ImageViewerGestureMath.onDoubleTap(
                                current = transform,
                                point = point,
                                viewport = viewport,
                                bitmap = bitmapSize,
                            )
                        },
                        onTap = { onDismiss() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = transform.zoom,
                        scaleY = transform.zoom,
                        translationX = transform.offset.x,
                        translationY = transform.offset.y,
                    ),
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
