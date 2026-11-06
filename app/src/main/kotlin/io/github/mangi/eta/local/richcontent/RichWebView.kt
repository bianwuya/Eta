package io.github.mangi.eta.local.richcontent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicBoolean

internal const val PREVIEW_HOST = "eta-local-preview.invalid"
internal const val PREVIEW_URL = "https://$PREVIEW_HOST/content/document.html"
internal const val INLINE_DOCUMENT_URL = "data:text/html;charset=utf-8;base64,"

/** Private virtual origin, no remote fallback and no native bridge. Never uses Agent browser sessions. */
@SuppressLint("SetJavaScriptEnabled")
internal class RichWebView(context: Context, scripts: Boolean, onBlocked: () -> Unit = {}, onFailed: (PreviewFailure) -> Unit = {}, onUserLink: ((Uri) -> Unit)? = null) : WebView(context) {
    @Volatile var page: String = ""
    @Volatile var resourceRoot: File? = null
    var onHeightChanged: ((Int) -> Unit)? = null
    /** 重建实例时恢复到的滚动位置；只在首个 onPageFinished 生效一次。 */
    var initialScrollY: Int = 0
    /** 每次滚动都把位置交给调用方保存，切换源码/刷新后可以回到原处。 */
    var onScrollYChanged: ((Int) -> Unit)? = null
    private var scrollRestored = false
    private var loadedOnce = false
    private var released = false
    private val pendingInlineDocument = AtomicBoolean(false)
    private var pending = false
    private var lastHeight = 0
    private val reportHeight = Runnable {
        pending = false
        if (!released && onHeightChanged != null) {
            val h = (contentHeight * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val tolerance = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            acceptedReportedHeight(lastHeight, h, tolerance)?.let { accepted ->
                lastHeight = accepted
                onHeightChanged?.invoke(accepted)
            }
        }
    }
    private val assets = WebViewAssetLoader.Builder().setDomain(PREVIEW_HOST)
        .addPathHandler("/math/", WebViewAssetLoader.AssetsPathHandler(context)).build()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = scripts
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> onBlocked() }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // Chromium also intercepts loadDataWithBaseURL's internal data: main request.
                // Admit only the one app-initiated load, not later page-created data navigation.
                if (request.isForMainFrame && request.method == "GET" && !request.hasGesture() &&
                    uri.toString() == INLINE_DOCUMENT_URL && pendingInlineDocument.compareAndSet(true, false)) return null
                // Self-contained image/font data never needs a network or file loader.
                if (!request.isForMainFrame && request.method == "GET" && isEmbeddedPreviewResource(uri.toString())) return null
                if (request.method != "GET" || !isPreviewOrigin(uri)) {
                    post { if (!released) onBlocked() }
                    return denied()
                }
                if (uri.path == "/content/document.html") return response("text/html", page.toByteArray())
                if (uri.path?.startsWith("/math/katex/") == true && uri.path?.contains("..") != true) {
                    assets.shouldInterceptRequest(uri)?.let { return it }
                }
                val relative = uri.path?.takeIf { it.startsWith("/content/") }?.removePrefix("/content/")
                val file = relative?.let { name -> resourceRoot?.let { LocalPreviewFiles.resource(it, name) } }
                if (file != null) {
                    return try {
                        val mime = when (file.extension.lowercase()) {
                            "js", "mjs" -> "application/javascript"
                            "css" -> "text/css"
                            "woff2" -> "font/woff2"
                            "woff" -> "font/woff"
                            else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                        }
                        WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), file.inputStream())
                    } catch (_: Exception) { denied() }
                }
                post { if (!released) onBlocked() }
                return denied()
            }
            // Even a user click cannot silently launch another app or navigate to credentials.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isPreviewDocument(request.url)) return false
                if (request.isForMainFrame && request.hasGesture() && onUserLink != null && request.url.scheme in setOf("https", "http", "mailto")) {
                    onUserLink(request.url)
                    return true
                }
                onBlocked()
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // A failed font/image must not replace an otherwise usable document.
                if (request.isForMainFrame && !released) {
                    pendingInlineDocument.set(false)
                    reportFailure(PreviewFailure("load", error.errorCode), onFailed)
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && !released) {
                    pendingInlineDocument.set(false)
                    reportFailure(PreviewFailure("http", errorResponse.statusCode), onFailed)
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                pendingInlineDocument.set(false)
                post { if (!released) reportFailure(PreviewFailure(if (detail.didCrash()) "renderer_crash" else "renderer_exit"), onFailed) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != PREVIEW_URL) return
                pendingInlineDocument.set(false)
                val target = pendingScrollRestore(initialScrollY, scrollRestored) ?: return
                if (released) return
                scrollRestored = true
                // 恢复放在页面完成之后，避免在空白文档上滚动被后续布局归零。
                post { if (!released) scrollTo(0, target) }
            }
        }
    }

    /**
     * 装载文档并返回是否真的发起了新加载。
     *
     * 同一实例对同一份内容重复 show 不再 loadUrl：普通重组、提示状态变化不应该让页面重来一遍。
     * 需要真正刷新时由调用方换实例键（页面重建），那时是新的 WebView，第一次 show 照常加载。
     */
    fun show(html: String, restoreScrollY: Int = 0): Boolean {
        initialScrollY = restoreScrollY
        if (loadedOnce && page == html) return false
        page = html
        loadedOnce = true
        scrollRestored = false
        // The HTML is already in memory: load it directly instead of making the
        // main document depend on an intercepted synthetic HTTPS navigation.
        // Keep the same private HTTPS origin for CSP and relative local resources.
        pendingInlineDocument.set(true)
        try {
            loadDataWithBaseURL(PREVIEW_URL, html, "text/html", "UTF-8", null)
        } catch (e: RuntimeException) {
            pendingInlineDocument.set(false)
            throw e
        }
        return true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val callback = onScrollYChanged
        if (!released && callback != null && t != oldt) callback(t)
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!released && onHeightChanged != null && !pending) { pending = true; post(reportHeight) }
    }

    fun release() {
        if (released) return
        released = true
        pendingInlineDocument.set(false)
        removeCallbacks(reportHeight)
        onHeightChanged = null
        onScrollYChanged = null
        stopLoading()
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }
}

internal data class PreviewFailure(val stage: String, val code: Int? = null) {
    val summary: String get() = when (stage) {
        "http" -> "本地文档加载失败（HTTP $code）"
        "load" -> "WebView 加载失败（错误码 $code）"
        "renderer_crash" -> "网页渲染进程异常退出"
        else -> "网页渲染进程已被系统结束"
    }
}

private fun reportFailure(failure: PreviewFailure, callback: (PreviewFailure) -> Unit) {
    // Fixed stage/code only: never log a file path, document body, or data URL.
    android.util.Log.w("EtaRichPreview", "stage=${failure.stage} code=${failure.code}")
    callback(failure)
}

internal fun isPreviewOrigin(uri: Uri): Boolean = uri.scheme == "https" && uri.host == PREVIEW_HOST &&
    uri.port in setOf(-1, 443) && uri.userInfo == null

internal fun isPreviewDocument(uri: Uri): Boolean = isPreviewOrigin(uri) && uri.path == "/content/document.html"

/**
 * WebView 在 onDraw 里回报内容高度，而容器高度又由这个值决定：任何 1px 的测量抖动都会变成
 * 改高 → 重排 → 再 onDraw 的自激循环，表现就是公式块持续抖动。
 * 只有相对上次接受值超过容差的变化才继续上报；容差内视为同一高度，不再回灌。
 */
internal fun acceptedReportedHeight(previous: Int, reported: Int, tolerancePx: Int): Int? = when {
    reported <= 0 -> null
    previous <= 0 -> reported
    kotlin.math.abs(reported - previous) < tolerancePx.coerceAtLeast(1) -> null
    else -> reported
}

internal fun pendingScrollRestore(initial: Int, restored: Boolean): Int? =
    if (restored) null else initial.coerceAtLeast(0)

private fun response(type: String, bytes: ByteArray) = WebResourceResponse(
    type, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes),
)
private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))

internal fun previewHtml(source: String): String {
    require(source.isNotBlank()) { "HTML 文件为空" }
    val doc = org.jsoup.Jsoup.parse(source)
    // Preserve original CSS and scripts; network/native access is governed by CSP and the client above.
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'")
    if (doc.selectFirst("meta[name=viewport]") == null) {
        doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    }
    return doc.outerHtml()
}

/** Only passive inline resources may bypass the local response loader; never a data: document. */
internal fun isEmbeddedPreviewResource(url: String): Boolean {
    val mime = url.substringBefore(',').substringBefore(';').lowercase()
    return mime.startsWith("data:image/") || mime.startsWith("data:font/") ||
        mime in setOf("data:application/font-woff", "data:application/x-font-woff", "data:application/x-font-ttf")
}
