package io.github.mangi.eta.ui.markdown

import io.github.mangi.eta.local.richcontent.*
import org.junit.Assert.*
import org.junit.Test

class RichMarkdownParsingTest {
    private fun parse(source: String) = StreamingGfmParserSession().parse(source, true)
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
                assertTrue(it.text.getStringAnnotations(MATH_ANNOTATION, 0, it.text.length).isEmpty())
            }
        }
    }
    @Test fun mathematicalSourceIsRetainedSeparatelyFromDisplayAnnotations() {
        val source = "行内 \\(\\frac{1}{2}\\)\n\n\\[x^2\\]"
        val snap = parse(source)
        assertEquals(source, snap.originalSource)
        val para = snap.document.blocks.first() as MarkdownParagraph
        assertEquals(1, para.text.getStringAnnotations(MATH_ANNOTATION, 0, para.text.length).size)
        assertTrue((snap.document.blocks.last() as MarkdownCode).isMath)
    }
    @Test fun explicitHtmlFenceAndRawHtmlHavePreviewLanguage() {
        listOf("```html\n<h1>demo</h1>\n```", "<html><body>demo</body></html>").forEach {
            val code = parse(it).document.blocks.single() as MarkdownCode
            assertEquals("html", code.language)
            assertFalse(code.isMath)
        }
    }
}
