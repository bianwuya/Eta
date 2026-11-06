package io.github.mangi.eta.local.richcontent

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HTML_CACHE_DIRECTORY = "external-html-preview"
private const val MAX_CACHED_HTML_FILES = 8
private val htmlCacheLock = Any()

/** Opens one self-contained HTML copy. Network and script policy belong to the external browser. */
internal suspend fun openExternalHtmlPreview(context: Context, reference: String) {
    val app = context.applicationContext
    val intent = withContext(Dispatchers.IO) {
        val uri = copyExternalHtmlPreview(app, reference)
        externalHtmlPreviewIntent(app, uri)
    }
    withContext(Dispatchers.Main.immediate) {
        try {
            app.startActivity(intent)
        } catch (error: android.content.ActivityNotFoundException) {
            throw IllegalStateException("浏览器无法打开 HTML，请安装支持本地 HTML 的浏览器", error)
        } catch (error: SecurityException) {
            throw IllegalStateException("浏览器无法读取 HTML 临时副本", error)
        }
    }
}

/** Only this copy is shared, never its original directory or adjacent assets. */
internal fun copyExternalHtmlPreview(context: Context, reference: String): Uri {
    val bytes = LocalPreviewFiles.open(context, reference).use {
        it.readBytesBounded(LocalPreviewFiles.MAX_DOCUMENT_BYTES)
    }
    return synchronized(htmlCacheLock) {
        val directory = File(context.cacheDir, HTML_CACHE_DIRECTORY)
        check(directory.mkdirs() || directory.isDirectory) { "无法创建 HTML 临时目录" }
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_HTML_FILES - 1)?.forEach { old ->
                check(old.delete()) { "无法清理 HTML 临时副本" }
            }
        val copy = File.createTempFile("preview-", ".html", directory)
        try {
            copy.outputStream().use { it.write(bytes) }
            FileProvider.getUriForFile(context, externalHtmlAuthority(context), copy)
        } catch (error: Exception) {
            copy.delete()
            throw error
        }
    }
}

internal fun externalHtmlAuthority(context: Context) = "${context.packageName}.external-html"

private fun browserIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
    .addCategory(Intent.CATEGORY_BROWSABLE)

@Suppress("DEPRECATION")
private fun defaultBrowserPackage(context: Context): String? = context.packageManager
    .resolveActivity(browserIntent(), PackageManager.MATCH_DEFAULT_ONLY)
    ?.takeIf { it.handleAllWebDataURI }?.activityInfo?.packageName

@Suppress("DEPRECATION")
internal fun externalHtmlPreviewIntent(
    context: Context,
    uri: Uri,
    defaultBrowserPackage: String? = defaultBrowserPackage(context),
): Intent {
    require(uri.scheme == "content" && uri.authority == externalHtmlAuthority(context)) {
        "仅能分享 HTML 临时副本"
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        clipData = ClipData.newRawUri("HTML", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val pm = context.packageManager
    val htmlHandlers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }.toSet()
    // The resolved general HTTPS handler is a browser; check its local HTML support too.
    if (defaultBrowserPackage != null && defaultBrowserPackage in htmlHandlers) {
        return view.setPackage(defaultBrowserPackage)
    }
    // BROWSABLE alone also matches deep-link apps; require a general HTTPS browser handler.
    val browsers = pm.queryIntentActivities(
        browserIntent(),
        PackageManager.MATCH_DEFAULT_ONLY,
    ).filter { it.handleAllWebDataURI }.map { it.activityInfo.packageName }.toSet()
    val candidates = browsers.intersect(htmlHandlers).sorted()
    check(candidates.isNotEmpty()) { "没有支持本地 HTML 的浏览器，请安装或启用浏览器" }
    if (candidates.size == 1) {
        return view.setPackage(candidates.single())
    }
    // Every chooser entry is package-scoped: HTML editors cannot enter this chooser.
    val targets = candidates.map { Intent(view).setPackage(it) }
    return Intent.createChooser(targets.first(), "选择浏览器").apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = view.clipData
    }
}

/** FileProvider's URI grants are read-only even if a recipient requests a write mode. */
class ExternalHtmlPreviewProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("HTML preview is read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("HTML preview is read-only")
}
