package io.github.mangi.eta.local.richcontent

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, instrumentedPackages = ["androidx.core.content"],
    shadows = [io.github.mangi.eta.testing.HostFileProviderPathShadow::class])
class AttachmentPreviewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearProviderCache() {
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        // Pin this fixture to one spelling of the Windows junction; FileProvider
        // computes relative URI paths by canonical string offsets.
        if (File.separatorChar == '\\') org.robolectric.util.ReflectionHelpers.setField(
            context.baseContext, "mCacheDir", context.cacheDir.canonicalFile,
        )
    }

    @Test fun utf8AndChineseLegacyEncodingsAreReadableWithoutReplacingBytes() {
        val text = "附件内容，核对后再发送。\n第二行"
        val utf8 = text.toByteArray()
        val legacy = text.toByteArray(Charset.forName("GB18030"))
        assertEquals(AttachmentText(text, "UTF-8"), decodeAttachmentText(utf8))
        assertEquals(AttachmentText(text, "GB18030"), decodeAttachmentText(legacy))
        assertArrayEquals(text.toByteArray(Charset.forName("GB18030")), legacy)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { decodeAttachmentText(legacy, "UTF-8") }
        assertEquals(text, decodeAttachmentText(legacy, "GB18030").text)
    }

    @Test fun bomEmptyFilesAndBinaryFailuresRemainExplicit() {
        val text = "中文 UTF-16"
        assertEquals(text, decodeAttachmentText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()).text)
        assertEquals(text, decodeAttachmentText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)).text)
        assertEquals(text, decodeAttachmentText(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)).text)
        assertEquals("", decodeAttachmentText(byteArrayOf()).text)
        assertThrows(IllegalArgumentException::class.java) { decodeAttachmentText(byteArrayOf(0, 1, 2)) }
    }

    @Test fun documentsKeepOriginalNameBytesMimeAndReadOnlyPermissions() {
        val provider = Robolectric.buildContentProvider(ExternalHtmlPreviewProvider::class.java).create(
            context.packageManager.getProviderInfo(ComponentName(context, ExternalHtmlPreviewProvider::class.java), PackageManager.GET_META_DATA),
        ).get()
        val source = File(context.filesDir, "中文 #100%+附件.docx").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4, 0, -1)) }
        try {
            for ((format, mime) in mapOf("pdf" to "application/pdf", "doc" to "application/msword",
                "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
                val name = "中文 #100%+附件.$format"
                val uri = copyExternalAttachment(context, source.path, name)
                val intent = externalAttachmentIntent(context, uri, name)
                provider.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                    assertNotNull(cursor)
                    assertTrue(requireNotNull(cursor).moveToFirst())
                    assertEquals(name, cursor.getString(0))
                }
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(mime, intent.type)
                assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(0, intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                val descriptor = provider.openFile(uri, "r")
                val copy = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                assertArrayEquals(source.readBytes(), copy)
                assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
                assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                externalAttachmentIntent(context, Uri.parse("file://${source.path}"), source.name)
            }
        } finally { source.delete() }
    }

    @Test fun contentUrisCopyBeforeGrantLossAndMissingOrOversizedInputsFail() {
        val uri = Uri.parse("content://attachment-test/document")
        val bytes = "original document".toByteArray()
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        copyExternalAttachment(context, uri.toString(), "document.pdf")
        val copies = File(context.cacheDir, "external-attachment-preview").walkTopDown().filter { it.isFile }.toList()
        assertArrayEquals(bytes, copies.single().readBytes())
        assertThrows(FileNotFoundException::class.java) { copyExternalAttachment(context, File(context.filesDir, "missing.pdf").path, "missing.pdf") }
        val large = File(context.filesDir, "large.pdf")
        try {
            java.io.RandomAccessFile(large, "rw").use { it.setLength(MAX_ATTACHMENT_PREVIEW_BYTES.toLong() + 1) }
            assertThrows(IllegalArgumentException::class.java) { copyExternalAttachment(context, large.path, large.name) }
        } finally { large.delete() }
    }

    @Test fun cacheIsBoundedAndDoesNotExposeOriginalDirectories() {
        val source = File(context.filesDir, "source.pdf").apply { writeText("source") }
        try {
            repeat(12) { copyExternalAttachment(context, source.path, source.name) }
            assertEquals(8, File(context.cacheDir, "external-attachment-preview").listFiles()!!.size)
            assertEquals("source", source.readText())
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, externalHtmlAuthority(context), source) }
        } finally { source.delete() }
    }
}
