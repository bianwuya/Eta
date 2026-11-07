package io.github.mangi.eta.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/** All translations and gesture coordinates are in unscaled viewport pixels. */
internal data class ImageViewerTransform(
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
)

internal object ImageViewerGestureMath {
    private const val MAX_ZOOM = 5f

    /**
     * Keep the bitmap point beneath [centroid] attached to the fingers while they
     * move by [pan] and the image scales by [zoomChange]. Both points must be
     * measured by the untransformed viewport, never by the scaled Image layer.
     */
    fun onGesture(
        current: ImageViewerTransform,
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        viewport: IntSize,
        bitmap: IntSize,
    ): ImageViewerTransform {
        val nextZoom = (current.zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val effectiveRatio = nextZoom / current.zoom
        val nextOffset = current.offset + pan +
            (centroid - center - current.offset) * (1f - effectiveRatio)
        return constrained(nextZoom, nextOffset, viewport, bitmap)
    }

    fun onDoubleTap(
        current: ImageViewerTransform,
        point: Offset,
        viewport: IntSize,
        bitmap: IntSize,
    ): ImageViewerTransform {
        val nextZoom = if (current.zoom > 1.05f) 1f else 2.5f
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = current.offset +
            (point - center - current.offset) * (1f - nextZoom / current.zoom)
        return constrained(nextZoom, nextOffset, viewport, bitmap)
    }

    private fun constrained(
        zoom: Float,
        offset: Offset,
        viewport: IntSize,
        bitmap: IntSize,
    ): ImageViewerTransform {
        if (zoom <= 1f || viewport.width <= 0 || viewport.height <= 0) {
            return ImageViewerTransform()
        }
        val fit = minOf(
            viewport.width.toFloat() / bitmap.width.coerceAtLeast(1),
            viewport.height.toFloat() / bitmap.height.coerceAtLeast(1),
        )
        val maxX = maxOf(0f, (bitmap.width * fit * zoom - viewport.width) / 2f)
        val maxY = maxOf(0f, (bitmap.height * fit * zoom - viewport.height) / 2f)
        return ImageViewerTransform(
            zoom = zoom,
            offset = Offset(
                offset.x.coerceIn(-maxX, maxX),
                offset.y.coerceIn(-maxY, maxY),
            ),
        )
    }
}
