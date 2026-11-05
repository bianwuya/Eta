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

    /** 与服务端单图产出上限对齐，避免已生成图片因为本地读取限过小而无法预览。 */
    const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
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
        require(uri.scheme == null || uri.scheme == "file") { "不是本地文件" }
        // file: URI 的 path 已由 Uri 解码；裸路径需要自己解码百分号转义，否则中文/空格路径
        // 在 isReference 通过后仍会打开失败。只解码一次，随后的规范化与白名单校验照旧。
        // 裸路径里的 ?query/#fragment 与 isReference 一样先剥离，否则带版本参数的引用永远打不开。
        val path = if (uri.scheme == "file") requireNotNull(uri.path)
        else percentDecodedPath(raw.substringBefore('?').substringBefore('#'))
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

/**
 * 只解码一次 `%XX`（按 UTF-8），不把 `+` 当作空格：路径不是查询参数。
 * 无法解码的转义原样保留，避免把损坏输入变成另一个合法路径；
 * 调用方随后仍执行 canonicalFile 规范化与目录白名单校验，所以 `%2F` 不能绕过边界。
 */
internal fun percentDecodedPath(raw: String): String {
    if ('%' !in raw) return raw
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '%' && index + 2 < raw.length) {
            val high = hexValue(raw[index + 1])
            val low = hexValue(raw[index + 2])
            if (high >= 0 && low >= 0) {
                // 收集连续的转义字节后按 UTF-8 解码，中文等多字节序列才能还原。
                val bytes = java.io.ByteArrayOutputStream(8)
                var cursor = index
                while (cursor + 2 < raw.length && raw[cursor] == '%') {
                    val hi = hexValue(raw[cursor + 1])
                    val lo = hexValue(raw[cursor + 2])
                    if (hi < 0 || lo < 0) break
                    bytes.write((hi shl 4) or lo)
                    cursor += 3
                }
                val decoded = String(bytes.toByteArray(), Charsets.UTF_8)
                // 非法 UTF-8 会被替换成 U+FFFD；这种情况下保留原文更接近用户的真实路径。
                if ('\uFFFD' !in decoded) {
                    out.append(decoded)
                    index = cursor
                    continue
                }
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

/** 卡片上显示的文件名：只用于展示，不参与路径解析。 */
internal fun percentName(reference: String): String =
    percentDecodedPath(reference.substringBefore('?').substringBefore('#')).substringAfterLast('/')

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
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
