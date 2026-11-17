package io.github.mangi.eta.ui.markdown

import io.github.mangi.eta.local.richcontent.*
import org.junit.Assert.*
import org.junit.Test

class RichMarkdownParsingTest {
    private fun parse(source: String) = StreamingGfmParserSession(
        MarkdownMathLayoutCache(
            engine = { _, _ ->
                io.ratex.DisplayList(width = 1.0, height = 0.8, depth = 0.2, items = emptyList())
            },
        ),
    ).parse(source, true)
    @Test fun localImageProducesDisplayAnnotationButNoImageBytes() {
        val source = "![预览](/sdcard/Download/Eta-前端审核/图.png)"
        val snap = parse(source)
        assertEquals(source, snap.originalSource)
        val text = (snap.document.blocks.single() as MarkdownParagraph).text
        assertEquals("预览", text.text)
        assertEquals("/sdcard/Download/Eta-前端审核/图.png", text.getStringAnnotations(IMAGE_ANNOTATION, 0, text.length).single().item)
        assertFalse(text.text.contains("base64"))
    }
    @Test fun htmlFileLinkKeepsLabelAndAddsOutputReference() {
        val snap = parse("[打开页面](/storage/emulated/0/Download/Eta-HTML/page.html)")
        val text = (snap.document.blocks.single() as MarkdownParagraph).text
        assertEquals("打开页面", text.text)
        assertEquals(1, text.getStringAnnotations(FILE_ANNOTATION, 0, text.length).size)
    }
    @Test fun remoteImagesDoNotBecomeAutomaticallyLoadedLocalCards() {
        val text = (parse("![remote](https://example.invalid/a.png)").document.blocks.single() as MarkdownParagraph).text
        assertTrue(text.getStringAnnotations(IMAGE_ANNOTATION, 0, text.length).isEmpty())
    }
    @Test fun unclosedMathAndCodeDoNotBecomeMathViews() {
        listOf("未闭合 \\(x", "`\\(x\\)`", "金额 \$20", "```latex\n\\frac{1}{2}\n```").forEach { source ->
            val snap = parse(source)
            assertEquals(source, snap.originalSource)
            snap.document.blocks.filterIsInstance<MarkdownTextBlock>().forEach {
                assertTrue(it.inlineMath.isEmpty())
            }
            assertTrue(snap.document.blocks.filterIsInstance<MarkdownMath>().isEmpty())
        }
    }
    @Test fun mathematicalSourceIsRetainedSeparatelyFromDisplayAnnotations() {
        val source = "行内 \\(\\frac{1}{2}\\)\n\n\\[x^2\\]"
        val snap = parse(source)
        assertEquals(source, snap.originalSource)
        val para = snap.document.blocks.first() as MarkdownParagraph
        assertEquals(1, para.inlineMath.size)
        assertEquals("\\frac{1}{2}", para.inlineMath.single().latex)
        val display = snap.document.blocks.last() as MarkdownMath
        assertEquals("x^2", display.latex)
    }
    @Test fun explicitHtmlFenceAndRawHtmlHavePreviewLanguage() {
        listOf("```html\n<h1>demo</h1>\n```", "<html><body>demo</body></html>").forEach {
            val code = parse(it).document.blocks.single() as MarkdownCode
            assertEquals("html", code.language)
        }
    }
}
