package io.github.mangi.eta.local.richcontent

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RichWebViewPolicyTest {
    private fun request(url: String, verb: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
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
}
