package io.github.mangi.eta.local.richcontent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RichContentTest {
    @Test fun onlyExplicitLocalOutputReferencesBecomeCards() {
        listOf("/sdcard/Download/页面/示例.html", "/storage/emulated/0/Download/a b.png", "file:///sdcard/Download/a.htm", "content://test/preview.jpg").forEach {
            assertTrue(it, LocalPreviewFiles.isReference(it))
        }
        listOf("https://example.com/a.html", "javascript:alert(1)", "/data/data/app/secret.html", "/sdcard/DCIM/a.png", "/sdcard/Download/file.txt", "outputs/a.html").forEach {
            assertFalse(it, LocalPreviewFiles.isReference(it))
        }
    }
    @Test fun imageDetectionHandlesQueriesAndMixedCase() {
        assertTrue(LocalPreviewFiles.isImage("/sdcard/Download/a.PNG?view=1"))
        assertFalse(LocalPreviewFiles.isImage("/sdcard/Download/a.html"))
    }
    @Test fun resourcePolicyRejectsTraversalHiddenFilesAndUnsupportedTypes() {
        val root = Files.createTempDirectory("rich-output").toFile()
        try {
            File(root, "a.css").writeText("body{}")
            File(root, "secret.txt").writeText("fixture")
            File(root, ".private.js").writeText("fixture")
            assertNotNull(LocalPreviewFiles.resource(root, "a.css"))
            listOf("../a.css", "x/../../a.css", ".private.js", "secret.txt", "a.html/../../a.css", "sub\\a.css", "missing.css").forEach {
                assertNull(it, LocalPreviewFiles.resource(root, it))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun resourcePolicyRejectsSymlinkOutsideRoot() {
        val root = Files.createTempDirectory("rich-output").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            val secret = File(outside, "outside.js").apply { writeText("fixture") }
            Files.createSymbolicLink(File(root, "escape.js").toPath(), secret.toPath())
            assertNull(LocalPreviewFiles.resource(root, "escape.js"))
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun bareDownloadsDoesNotExposeAllOtherFiles() {
        val parent = Files.createTempDirectory("rich-parent").toFile()
        try {
            val root = File(parent, "Download").apply { mkdirs() }
            File(root, "a.js").writeText("fixture")
            assertNull(LocalPreviewFiles.resource(root, "a.js"))
        } finally { parent.deleteRecursively() }
    }
    @Test fun boundedReaderAcceptsLimitAndRejectsOverflow() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBytesBounded(2))
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesBounded(2) }
    }
    @Test fun htmlPreviewPreservesSourceScriptsButAddsPolicyAndViewport() {
        val result = previewHtml("<html><head><style>.a{color:red}</style></head><body><button onclick=\"go()\">ok</button><script>function go(){document.body.title='ok'}</script></body></html>")
        assertTrue(result.contains("function go()"))
        assertTrue(result.contains(".a{color:red}"))
        assertTrue(result.contains("Content-Security-Policy"))
        assertTrue(result.contains("default-src 'none'"))
        assertTrue(result.contains("form-action 'none'"))
        assertTrue(result.contains("name=\"viewport\""))
    }
    @Test fun viewportIsNotDuplicated() {
        val doc = org.jsoup.Jsoup.parse(previewHtml("<meta name=viewport content='width=800'><p>x</p>"))
        assertEquals(1, doc.select("meta[name=viewport]").size)
        assertEquals("width=800", doc.selectFirst("meta[name=viewport]")!!.attr("content"))
    }
    @Test fun mathInputIsHtmlEscapedNotInterpolatedIntoScript() {
        val tex = "</script><img src=x onerror=alert(1)>"
        val text = AnnotatedString.Builder().apply {
            append("before ")
            pushStringAnnotation(MATH_ANNOTATION, "inline"); append(tex); pop()
            append(" after")
        }.toAnnotatedString()
        val html = mathDocument(text, 16f, 24f, Color.Black, false)
        assertFalse(html.contains(tex))
        assertTrue(html.contains("&lt;/script&gt;"))
        assertTrue(html.contains("trust:false"))
        assertTrue(html.contains("maxExpand:500"))
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse(html.contains("https://cdn"))
    }
    @Test fun mixedParagraphKeepsMathAndBoldInOrder() {
        val text = AnnotatedString.Builder().apply {
            append("正文 ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append("加粗"); pop()
            pushStringAnnotation(MATH_ANNOTATION, "inline"); append("x_1"); pop()
            append(" 结尾")
        }.toAnnotatedString()
        val html = mathDocument(text, 16f, 24f, Color.White, false)
        assertTrue(html.contains("font-weight:600"))
        assertTrue(html.contains("<span data-math>x_1</span>"))
        assertTrue(html.contains("#ffffff"))
        assertTrue(html.indexOf("正文") < html.indexOf("data-math>x_1"))
    }
    @Test fun displayMathUsesSeparateLayoutWithoutCodeBackground() {
        val html = mathDocument(AnnotatedString("\\frac{1}{2}"), 16f, 24f, Color.Black, true)
        assertTrue(html.contains("data-display=\"true\""))
        assertTrue(html.contains("background:transparent"))
        assertTrue(html.contains("overflow-x:auto"))
    }
}
