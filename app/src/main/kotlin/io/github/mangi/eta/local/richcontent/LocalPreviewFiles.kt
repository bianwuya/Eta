package io.github.mangi.eta.local.richcontent

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** UI-only output references. This is deliberately not a Root/file-tool bridge. */
internal object LocalPreviewFiles {
    const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private val extensions = setOf("html", "htm", "png", "jpg", "jpeg", "webp", "gif")
    private val resources = extensions + setOf("css", "js", "mjs", "svg", "woff", "woff2", "ttf", "otf", "json")

    fun isReference(raw: String): Boolean {
        if (raw.startsWith("content://")) return true // MIME is resolved only when explicitly opened.
        val path = raw.substringBefore('?').substringBefore('#')
        if (path.substringAfterLast('.', "").lowercase() !in extensions) return false
        return path.startsWith("/sdcard/Download/") || path.startsWith("/storage/emulated/0/Download/") ||
            path.startsWith("file:///sdcard/Download/") || path.startsWith("file:///storage/emulated/0/Download/") ||
            path.startsWith("content://")
    }

    fun isSupported(raw: String) = raw.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in extensions

    fun isImage(raw: String) = raw.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in
        setOf("png", "jpg", "jpeg", "webp", "gif")

    fun directory(context: Context) = File(context.cacheDir, "rich-preview").apply { mkdirs() }

    @Suppress("DEPRECATION")
    fun file(context: Context, raw: String): File {
        val uri = Uri.parse(raw)
        val path = if (uri.scheme == "file") requireNotNull(uri.path) else raw
        require(uri.scheme == null || uri.scheme == "file") { "不是本地文件" }
        val target = File(path).canonicalFile
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val cache = directory(context).canonicalFile
        val imports = File(io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir), "imports").canonicalFile
        require(isWithin(target, downloads) || isWithin(target, cache) || isWithin(target, imports)) { "仅预览 Downloads 中的输出文件或已授权附件" }
        require(target.extension.lowercase() in extensions) { "暂不支持这种预览格式" }
        require(target.isFile) { "文件不存在或已移走" }
        return target
    }

    fun open(context: Context, raw: String): InputStream = if (raw.startsWith("content://")) {
        context.contentResolver.openInputStream(Uri.parse(raw)) ?: error("无法读取附件，请重新授权")
    } else file(context, raw).inputStream()

    fun text(context: Context, raw: String): String = open(context, raw).use { input ->
        val bytes = input.readBytesBounded(MAX_DOCUMENT_BYTES)
        bytes.toString(Charsets.UTF_8)
    }

    fun saveCode(context: Context, html: String): File {
        require(html.toByteArray().size <= MAX_DOCUMENT_BYTES) { "HTML 超过 2 MiB，请保存为文件后打开" }
        val digest = MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        val root = directory(context)
        // Only caches created by this viewer; user outputs are never cleaned here.
        root.listFiles()?.filter { it.name.endsWith(".html") }?.sortedByDescending { it.lastModified() }
            ?.drop(19)?.forEach { it.delete() }
        return File(root, "$digest.html").apply { writeText(html) }
    }

    /** No directory listing, traversal, symlink escape, or bare Downloads-wide resource access. */
    fun resource(root: File, relative: String): File? {
        if (relative.contains('\\') || relative.split('/').any { it == ".." || it.startsWith('.') }) return null
        if (relative.substringAfterLast('.', "").lowercase() !in resources) return null
        val canonicalRoot = root.canonicalFile
        if (canonicalRoot.name == "Download") return null
        val candidate = File(root, relative).canonicalFile
        return candidate.takeIf { isWithin(it, canonicalRoot) && it.isFile && it.length() <= MAX_IMAGE_BYTES }
    }

    fun isWithin(file: File, root: File) = file.path.startsWith(root.path + File.separator)
}

internal fun InputStream.readBytesBounded(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(out.size() + read <= max) { "文件过大（上限 ${max / 1024} KiB）" }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
