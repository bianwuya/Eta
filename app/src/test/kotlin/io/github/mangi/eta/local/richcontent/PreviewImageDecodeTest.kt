package io.github.mangi.eta.local.richcontent

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
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
    fun fullScreenLocalImageKeepsItsOriginalResolution() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LocalPreviewFiles.directory(context), "full-resolution-test.png")
        val bitmap = Bitmap.createBitmap(2_400, 800, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = decodePreviewImage(context, reference(file))
            assertEquals(2_400, decoded.width)
            assertEquals(800, decoded.height)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun localImageIsSampledWithoutConvertingItToADataUrl() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LocalPreviewFiles.directory(context), "preview-image-test.png")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = decodePreviewImage(context, reference(file), maxPixels = 16)
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
                decodePreviewImage(context, reference(file))
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
            val error = assertThrows(IllegalArgumentException::class.java) {
                decodePreviewImage(context, reference(file))
            }
            assertEquals("仅预览 Downloads 中的输出文件或已授权附件", error.message)
        } finally {
            file.delete()
        }
    }

    private fun reference(file: File) = Uri.encode(file.absolutePath, "/\\")
}
