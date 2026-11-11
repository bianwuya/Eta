package io.github.mangi.eta.local.richcontent

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, instrumentedPackages = ["androidx.core.content"],
    shadows = [io.github.mangi.eta.testing.HostFileProviderPathShadow::class])
class AttachmentStreamingCopyTest {
    @Before fun clearProviderCache() {
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").clear()
    }
    @Test fun thirtyTwoMiBCopyUsesABoundedReadBufferAndDoesNotPublishFailures() {
        val context = RuntimeEnvironment.getApplication()
        if (File.separatorChar == '\\') org.robolectric.util.ReflectionHelpers.setField(
            context.baseContext, "mCacheDir", context.cacheDir.canonicalFile)
        val uri = Uri.parse("content://streaming-fixture/document")
        class Generated(val length: Long, val failAfter: Long = Long.MAX_VALUE) : InputStream() {
            var position = 0L
            override fun read(): Int = error("bulk expected")
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                check(count <= 64 * 1024) { "unbounded bulk read: $count" }
                if (position >= failAfter) throw java.io.IOException("fixture failure")
                if (position >= length) return -1
                val n = minOf(count.toLong(), length - position).toInt()
                buffer.fill(7, offset, offset + n); position += n; return n
            }
        }
        val root = File(context.cacheDir, "external-attachment-preview")
        for (bytes in listOf(0L, 1L, 8L * 1024 * 1024, 32L * 1024 * 1024)) {
            shadowOf(context.contentResolver).registerInputStream(uri, Generated(bytes))
            copyExternalAttachment(context, uri.toString(), "stream-$bytes.pdf")
            assertTrue(root.walkTopDown().any { it.isFile && it.name == "stream-$bytes.pdf" && it.length() == bytes })
        }
        val before = root.listFiles()!!.toSet()
        shadowOf(context.contentResolver).registerInputStream(uri, Generated(1024 * 1024))
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            copyExternalAttachment(context, uri.toString(), "cancelled.pdf") {
                if (++checks == 2) throw kotlinx.coroutines.CancellationException("fixture")
            }
        }
        assertEquals(before, root.listFiles()!!.toSet())
        for (stream in listOf(Generated(33L * 1024 * 1024), Generated(Long.MAX_VALUE), Generated(100_000, 65_536))) {
            shadowOf(context.contentResolver).registerInputStream(uri, stream)
            assertThrows(Exception::class.java) { copyExternalAttachment(context, uri.toString(), "failed.pdf") }
            assertEquals(before, root.listFiles()!!.toSet())
            assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".part") })
        }
    }
}
