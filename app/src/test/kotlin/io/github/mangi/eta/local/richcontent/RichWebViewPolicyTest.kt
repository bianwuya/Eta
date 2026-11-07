package io.github.mangi.eta.local.richcontent

import android.net.Uri
import android.webkit.PreviewTestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RichWebViewPolicyTest {
    private fun request(url: String, verb: String = "GET", mainFrame: Boolean = false, gesture: Boolean = false) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = verb
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    @Test fun staticPreviewDoesNotEnableScriptsOrFileAccess() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), false)
        try {
            assertFalse(view.settings.javaScriptEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.domStorageEnabled)
            assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
        } finally { view.release() }
    }
    @Test fun evenInteractiveModeBlocksNetworkAndLocalSchemes() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), true)
        try {
            assertTrue(view.settings.javaScriptEnabled)
            listOf("https://example.com/track", "http://example.com/a", "file:///data/local/tmp/secret", "content://private/item", "https://$PREVIEW_HOST.evil.invalid/a").forEach {
                assertEquals(it, 403, view.webViewClient.shouldInterceptRequest(view, request(it))!!.statusCode)
            }
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request(PREVIEW_URL, "POST"))!!.statusCode)
        } finally { view.release() }
    }
    @Test fun onlyViewerDocumentIsServedAndUnrelatedAppAssetsAreDenied() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), false)
        try {
            view.page = "<p>fixture only</p>"
            val response = view.webViewClient.shouldInterceptRequest(view, request(PREVIEW_URL))!!
            assertEquals(200, response.statusCode)
            assertEquals("<p>fixture only</p>", response.data.bufferedReader().readText())
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request("https://$PREVIEW_HOST/math/private-config.json"))!!.statusCode)
        } finally { view.release() }
    }
    @Test fun codePreviewRoundTripsThroughPrivateCacheAndRejectsOutsideFiles() {
        val context = RuntimeEnvironment.getApplication()
        val file = LocalPreviewFiles.saveCode(context, "<h1>页面</h1>")
        assertEquals("<h1>页面</h1>", LocalPreviewFiles.text(context, file.path))
        assertThrows(IllegalArgumentException::class.java) { LocalPreviewFiles.file(context, "/data/local/tmp/secret.html") }
    }
    @Test fun documentLoadsDirectlyWithPrivateBaseAndDoesNotReloadOnRecomposition() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), false)
        try {
            val html = previewHtml("<h1>离线正文</h1><img src='asset.png'>")
            assertTrue(view.show(html, 120))
            val loaded = shadowOf(view).lastLoadDataWithBaseURL
            assertNotNull(loaded)
            assertEquals(PREVIEW_URL, loaded.baseUrl)
            assertEquals(html, loaded.data)
            assertEquals("text/html", loaded.mimeType)
            assertEquals("UTF-8", loaded.encoding)
            assertNull(shadowOf(view).lastLoadedUrl)
            assertFalse(view.show(html, 120))
            assertTrue(view.show(previewHtml("<p>更新正文</p>")))
        } finally { view.release() }
    }
    @Test fun inlineImagesAndFontsAreAllowedButNeverDataDocuments() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), true)
        try {
            listOf("data:image/png;base64,AA==", "data:font/woff2;base64,AA==").forEach { url ->
                assertNull(view.webViewClient.shouldInterceptRequest(view, request(url)))
                assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request(url, mainFrame = true))!!.statusCode)
            }
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request("data:text/html,<script>bad()</script>"))!!.statusCode)
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request("data:application/javascript,alert(1)"))!!.statusCode)
            val doc = org.jsoup.Jsoup.parse(previewHtml("<h1>正文</h1>"))
            val csp = doc.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
            assertTrue(csp.contains("font-src 'self' data:"))
            assertTrue(csp.contains("frame-src 'none'"))
            assertTrue(csp.contains("object-src 'none'"))
        } finally { view.release() }
    }
    @Test fun chromiumInternalMainDocumentIsAdmittedOncePerActualShow() {
        assertEquals("data:text/html;charset=utf-8;base64,", INLINE_DOCUMENT_URL)
        val view = RichWebView(RuntimeEnvironment.getApplication(), false)
        val internal = request(INLINE_DOCUMENT_URL, mainFrame = true)
        try {
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, internal)!!.statusCode)
            val html = previewHtml("<h1>正文</h1>")
            assertTrue(view.show(html))
            assertNull(view.webViewClient.shouldInterceptRequest(view, internal))
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, internal)!!.statusCode)
            assertFalse(view.show(html))
            assertEquals(403, view.webViewClient.shouldInterceptRequest(view, internal)!!.statusCode)
            assertTrue(view.show(previewHtml("<h1>更新</h1>")))
            assertNull(view.webViewClient.shouldInterceptRequest(view, internal))
        } finally { view.release() }
    }
    @Test fun onlyExactInternalRequestConsumesTheOneShotAdmission() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), true)
        try {
            view.show(previewHtml("<p>正文</p>"))
            val rejected = listOf(
                request(INLINE_DOCUMENT_URL),
                request(INLINE_DOCUMENT_URL, "POST", true),
                request(INLINE_DOCUMENT_URL, mainFrame = true, gesture = true),
                request(INLINE_DOCUMENT_URL + "PGgxPmJhZDwvaDE+", mainFrame = true),
                request(INLINE_DOCUMENT_URL + "#bad", mainFrame = true),
                request("data:text/plain,", mainFrame = true),
            )
            rejected.forEach { assertEquals(403, view.webViewClient.shouldInterceptRequest(view, it)!!.statusCode) }
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request(INLINE_DOCUMENT_URL, mainFrame = true)))
            assertNull(view.webViewClient.shouldInterceptRequest(view, request(INLINE_DOCUMENT_URL, mainFrame = true)))
        } finally { view.release() }
    }
    @Test fun pageFinishedAndFailureRevokeUnconsumedInlineAdmission() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), false)
        try {
            val client = view.webViewClient
            view.show(previewHtml("<p>正文</p>"))
            client.onPageFinished(view, PREVIEW_URL)
            assertEquals(403, client.shouldInterceptRequest(view, request(INLINE_DOCUMENT_URL, mainFrame = true))!!.statusCode)
            view.show(previewHtml("<p>更新</p>"))
            client.onReceivedError(view, request(PREVIEW_URL, mainFrame = true), PreviewTestWebResourceError())
            assertEquals(403, client.shouldInterceptRequest(view, request(INLINE_DOCUMENT_URL, mainFrame = true))!!.statusCode)
        } finally { view.release() }
    }
    @Test fun originAndNavigationUseTheSameHttpsBoundary() {
        val view = RichWebView(RuntimeEnvironment.getApplication(), true)
        try {
            val client = view.webViewClient
            listOf("http://$PREVIEW_HOST/content/document.html", "https://$PREVIEW_HOST:8443/content/document.html",
                "https://user@$PREVIEW_HOST/content/document.html").forEach { url ->
                assertEquals(403, client.shouldInterceptRequest(view, request(url, mainFrame = true))!!.statusCode)
                assertTrue(client.shouldOverrideUrlLoading(view, request(url, mainFrame = true)))
            }
            assertFalse(client.shouldOverrideUrlLoading(view, request(PREVIEW_URL + "#section", mainFrame = true)))
            assertEquals(200, client.shouldInterceptRequest(view, request("https://$PREVIEW_HOST:443/content/document.html"))!!.statusCode)
        } finally { view.release() }
    }
    @Test fun errorDetailsRetainOnlyStageAndCodeAndLateCallbacksAreIgnored() {
        val failures = mutableListOf<PreviewFailure>()
        val view = RichWebView(RuntimeEnvironment.getApplication(), false, onFailed = { failures += it })
        val client = view.webViewClient
        val httpError = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        client.onReceivedHttpError(view, request(PREVIEW_URL, mainFrame = true), httpError)
        assertEquals(listOf(PreviewFailure("http", 403)), failures)
        assertTrue(failures.single().summary.contains("403"))
        view.release()
        client.onReceivedHttpError(view, request(PREVIEW_URL, mainFrame = true), httpError)
        client.onReceivedError(view, request(PREVIEW_URL, mainFrame = true), PreviewTestWebResourceError())
        assertEquals(1, failures.size)
    }
    @Test fun mainFrameErrorsAreReportedButSubresourceErrorsAreNot() {
        var failures = 0
        val view = RichWebView(RuntimeEnvironment.getApplication(), false, onFailed = { failures++ })
        val error = PreviewTestWebResourceError()
        val httpError = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        try {
            val client = view.webViewClient
            client.onReceivedError(view, request(PREVIEW_URL), error)
            client.onReceivedHttpError(view, request(PREVIEW_URL), httpError)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, failures)
            client.onReceivedError(view, request(PREVIEW_URL, mainFrame = true), error)
            client.onReceivedHttpError(view, request(PREVIEW_URL, mainFrame = true), httpError)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, failures)
        } finally { view.release() }
    }
    @Test fun emptyHtmlHasAnExplicitErrorRatherThanABlankPreview() {
        assertThrows(IllegalArgumentException::class.java) { previewHtml("  \n ") }
    }

}
