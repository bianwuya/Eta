package io.github.mangi.eta.local.richcontent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

internal const val PREVIEW_HOST = "eta-local-preview.invalid"
internal const val PREVIEW_URL = "https://$PREVIEW_HOST/content/document.html"

/** Private virtual origin, no remote fallback and no native bridge. Never uses Agent browser sessions. */
@SuppressLint("SetJavaScriptEnabled")
internal class RichWebView(context: Context, scripts: Boolean, onBlocked: () -> Unit = {}, onFailed: () -> Unit = {}, onUserLink: ((Uri) -> Unit)? = null) : WebView(context) {
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (request.method != "GET" || uri.scheme != "https" || uri.host != PREVIEW_HOST) {
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
                if (request.url.host == PREVIEW_HOST && request.url.path == "/content/document.html") return false
                if (request.hasGesture() && onUserLink != null && request.url.scheme in setOf("https", "http", "mailto")) {
                    onUserLink(request.url)
                    return true
                }
                onBlocked()
                return true
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                post { if (!released) onFailed() }
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != PREVIEW_URL) return
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
        // 位置恢复放在页面完成之后，避免在空白文档上滚动被后续布局归零。
        loadUrl(PREVIEW_URL)
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
        removeCallbacks(reportHeight)
        onHeightChanged = null
        onScrollYChanged = null
        stopLoading()
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }
}

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
    val doc = org.jsoup.Jsoup.parse(source)
    // Preserve original CSS and scripts; network/native access is governed by CSP and the client above.
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'")
    if (doc.selectFirst("meta[name=viewport]") == null) {
        doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    }
    return doc.outerHtml()
}
