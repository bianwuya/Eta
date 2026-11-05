package io.github.mangi.eta.local.richcontent

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewImageDecodeTest {
    @Test
    fun localImageIsSampledWithoutConvertingItToADataUrl() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LocalPreviewFiles.directory(context), "preview-image-test.png")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = decodePreviewImage(context, file.path, maxPixels = 16)
            assertEquals(16, decoded.width)
            assertEquals(8, decoded.height)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun oversizedLocalImageIsRejectedAtTheExisting32MiBBoundary() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LocalPreviewFiles.directory(context), "preview-oversized-test.png")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(LocalPreviewFiles.MAX_IMAGE_BYTES.toLong() + 1) }
            val error = assertThrows(IllegalArgumentException::class.java) {
                decodePreviewImage(context, file.path)
            }
            assertEquals("文件过大（上限 32768 KiB）", error.message)
        } finally {
            file.delete()
        }
    }

    @Test
    fun localImagesOutsideTheAllowedDirectoriesAreRejected() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "preview-outside-test.png")
        try {
            file.writeBytes(byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java) { decodePreviewImage(context, file.path) }
        } finally {
            file.delete()
        }
    }
}
