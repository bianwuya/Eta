package io.github.mangi.eta.local.richcontent

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.model.AgentFileReference
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit user attachments have a separate path from untrusted Markdown output links. */
internal val LocalAttachmentPreview = staticCompositionLocalOf<((AgentFileReference) -> Unit)?> { null }

internal const val MAX_ATTACHMENT_PREVIEW_BYTES = 32 * 1024 * 1024
private const val ATTACHMENT_CACHE = "external-attachment-preview"
private val attachmentCacheLock = Any()
private val textExtensions = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "log", "ini", "conf")

internal fun isTextAttachment(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in textExtensions

internal fun openAttachmentSource(context: Context, reference: String): InputStream {
    // An explicit path is literal: '#' and '%' in an imported filename are not URL syntax.
    val file = File(reference)
    if (file.isAbsolute) return file.inputStream()
    val uri = Uri.parse(reference)
    return when (uri.scheme) {
        "content" -> context.contentResolver.openInputStream(uri) ?: error("无法读取附件，请重新授权")
        "file" -> File(requireNotNull(uri.path)).inputStream()
        else -> error("不是本地附件")
    }
}

internal data class AttachmentText(val text: String, val encoding: String)

internal fun decodeAttachmentText(bytes: ByteArray, encoding: String? = null): AttachmentText {
    fun decode(charset: Charset, offset: Int = 0): AttachmentText {
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString().removePrefix("\uFEFF")
        require(text.none { it == '\u0000' || (it.isISOControl() && it !in "\n\r\t\u000C") }) { "文件不是可读文本，请使用其他应用打开" }
        return AttachmentText(text, charset.name())
    }
    if (encoding != null) return decode(Charset.forName(encoding))
    fun starts(vararg values: Int): Boolean = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
    if (starts(0xEF, 0xBB, 0xBF)) return decode(Charsets.UTF_8, 3)
    if (starts(0xFF, 0xFE)) return decode(Charsets.UTF_16LE, 2)
    if (starts(0xFE, 0xFF)) return decode(Charsets.UTF_16BE, 2)
    return try { decode(Charsets.UTF_8) } catch (_: CharacterCodingException) {
        try { decode(Charset.forName("GB18030")) } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("无法识别文本编码，请切换编码或使用其他应用打开", error)
        }
    }
}

internal fun attachmentMimeType(name: String): String = when (val extension = name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "md", "markdown", "log", "ini", "conf" -> "text/plain"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

/** Preserve original bytes and name; expose only a bounded read-only cache copy. */
internal fun copyExternalAttachment(context: Context, reference: String, displayName: String): Uri {
    val bytes = openAttachmentSource(context, reference).use { it.readBytesBounded(MAX_ATTACHMENT_PREVIEW_BYTES) }
    return synchronized(attachmentCacheLock) {
        val root = File(context.cacheDir, ATTACHMENT_CACHE)
        check(root.mkdirs() || root.isDirectory) { "无法创建附件预览目录" }
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach {
            check(it.deleteRecursively()) { "无法清理附件预览副本" }
        }
        val directory = File(root, java.util.UUID.randomUUID().toString())
        check(directory.mkdir()) { "无法创建附件预览副本" }
        val name = displayName.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("").take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
        try {
            val copy = File(directory, name).apply { outputStream().use { it.write(bytes) } }
            FileProvider.getUriForFile(context, externalHtmlAuthority(context), copy)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }
}

internal fun externalAttachmentIntent(context: Context, uri: Uri, displayName: String): Intent {
    require(uri.scheme == "content" && uri.authority == externalHtmlAuthority(context) && uri.path.orEmpty().startsWith("/attachment/")) {
        "仅能打开附件预览副本"
    }
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachmentMimeType(displayName))
        clipData = ClipData.newRawUri(displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal suspend fun openExternalAttachment(context: Context, reference: String, displayName: String) {
    val app = context.applicationContext
    val intent = withContext(Dispatchers.IO) {
        externalAttachmentIntent(app, copyExternalAttachment(app, reference, displayName), displayName)
    }
    withContext(Dispatchers.Main.immediate) {
        try { app.startActivity(intent) }
        catch (error: android.content.ActivityNotFoundException) {
            throw IllegalStateException("没有可打开此格式的应用，请安装 PDF 或 Office 阅读器后重试", error)
        } catch (error: SecurityException) {
            throw IllegalStateException("阅读器无法读取附件预览副本", error)
        }
    }
}
