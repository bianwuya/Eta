package io.github.mangi.eta.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewerGestureMathTest {
    private val square = IntSize(400, 400)

    @Test
    fun `drag at 3x follows pointer in screen pixels without slowing down`() {
        val start = ImageViewerTransform(zoom = 3f, offset = Offset(25f, -20f))
        val result = ImageViewerGestureMath.onGesture(
            current = start,
            centroid = Offset(245f, 230f),
            pan = Offset(57f, -31f),
            zoomChange = 1f,
            viewport = square,
            bitmap = square,
        )
        assertEquals(82f, result.offset.x, 0.001f)
        assertEquals(-51f, result.offset.y, 0.001f)
    }

    @Test
    fun `pinch keeps the selected bitmap pixel under the touch centroid`() {
        val center = Offset(200f, 200f)
        val centroid = Offset(300f, 100f)
        val start = ImageViewerTransform(zoom = 2f, offset = Offset(30f, -20f))
        val selectedPixel = (centroid - center - start.offset) / start.zoom
        val result = ImageViewerGestureMath.onGesture(
            current = start,
            centroid = centroid,
            pan = Offset(10f, 5f),
            zoomChange = 1.5f,
            viewport = square,
            bitmap = square,
        )
        val screenPosition = center + result.offset + selectedPixel * result.zoom
        assertEquals(310f, screenPosition.x, 0.001f)
        assertEquals(105f, screenPosition.y, 0.001f)
    }

    @Test
    fun `double tap zoom preserves tapped pixel position`() {
        val point = Offset(300f, 120f)
        val zoomed = ImageViewerGestureMath.onDoubleTap(
            current = ImageViewerTransform(),
            point = point,
            viewport = square,
            bitmap = square,
        )
        val center = Offset(200f, 200f)
        val selectedPixel = point - center
        val afterZoom = center + zoomed.offset + selectedPixel * zoomed.zoom
        assertEquals(2.5f, zoomed.zoom, 0.001f)
        assertEquals(point.x, afterZoom.x, 0.001f)
        assertEquals(point.y, afterZoom.y, 0.001f)

        val reset = ImageViewerGestureMath.onDoubleTap(zoomed, point, square, square)
        assertEquals(1f, reset.zoom, 0.001f)
        assertEquals(Offset.Zero, reset.offset)
    }

    @Test
    fun `boundary clamps only axes where scaled image exceeds viewport`() {
        val result = ImageViewerGestureMath.onGesture(
            current = ImageViewerTransform(zoom = 2f),
            centroid = Offset(200f, 200f),
            pan = Offset(900f, 900f),
            zoomChange = 1f,
            viewport = square,
            bitmap = IntSize(400, 200),
        )
        assertEquals(200f, result.offset.x, 0.001f)
        assertEquals(0f, result.offset.y, 0.001f)
    }

    @Test
    fun `maximum scale uses effective zoom ratio for the anchor`() {
        val center = Offset(200f, 200f)
        val centroid = Offset(280f, 160f)
        val start = ImageViewerTransform(zoom = 4f)
        val selectedPixel = (centroid - center) / start.zoom
        val result = ImageViewerGestureMath.onGesture(
            current = start,
            centroid = centroid,
            pan = Offset.Zero,
            zoomChange = 2f,
            viewport = square,
            bitmap = square,
        )
        assertEquals(5f, result.zoom, 0.001f)
        val screenPosition = center + result.offset + selectedPixel * result.zoom
        assertEquals(centroid.x, screenPosition.x, 0.001f)
        assertEquals(centroid.y, screenPosition.y, 0.001f)
    }
}
