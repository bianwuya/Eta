package io.github.mangi.eta.local.incoming

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class IncomingContentParserTest {
    @Test fun ignoresUnrelatedActionsAndEmptyPayloads() {
        assertNull(IncomingContentParser.fromIntent(Intent("other")))
        assertNull(IncomingContentParser.fromIntent(Intent(Intent.ACTION_SEND)))
    }

    @Test fun sharesTextAndLinksWithoutDownloadingThem() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.org/article")
        intent.clipData = ClipData.newPlainText("url", "https://example.org/article")
        val payload = IncomingContentParser.fromIntent(intent)!!
        assertEquals("https://example.org/article", payload.text)
        assertTrue(payload.uris.isEmpty())
    }

    @Test fun deduplicatesSingleStreamAndClipData() {
        val uri = Uri.parse("content://provider/item")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newRawUri("shared", uri).apply {
            addItem(ClipData.Item(Uri.parse("https://example.org/image.png")))
        }
        val payload = IncomingContentParser.fromIntent(intent)!!
        assertEquals(listOf(uri), payload.uris)
        assertEquals("image/png", payload.mimeType)
        assertEquals("https://example.org/image.png", payload.text)
    }

    @Test fun acceptsMultipleStreamsAndMixedClipWithoutDuplicatingText() {
        val first = Uri.parse("content://provider/first")
        val second = Uri.parse("content://provider/second")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
            .putExtra(Intent.EXTRA_TEXT, "caption")
        intent.clipData = ClipData.newPlainText("text", "caption").apply {
            addItem(ClipData.Item("caption", "<b>caption</b>", null, first))
            addItem(ClipData.Item(Uri.parse("https://example.org/a")))
        }
        val payload = IncomingContentParser.fromIntent(intent)!!
        assertEquals(listOf(first, second), payload.uris)
        assertEquals("caption\nhttps://example.org/a", payload.text)
    }

    @Test fun dragAcceptsPlainTextHtmlAndUrls() {
        val clip = ClipData.newHtmlText("html", "hello", "<b>hello</b>").apply {
            addItem(ClipData.Item(Uri.parse("https://example.org/")))
        }
        assertEquals("hello\nhttps://example.org/", IncomingContentParser.fromClipData(clip)!!.text)
        val payload = IncomingContentParser.fromIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_HTML_TEXT, "<b>Hello</b>")
        )!!
        assertEquals("Hello", payload.text)
    }

    @Test fun capsTextAndUrisAndReportsEveryLimit() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putExtra(Intent.EXTRA_TEXT, "x".repeat(100_010))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((0..24).map { Uri.parse("content://p/$it") }))
        val payload = IncomingContentParser.fromIntent(intent)!!
        assertEquals(100_000, payload.text.length)
        assertEquals(20, payload.uris.size)
        assertEquals(6, payload.skipped)
    }

    @Test fun rejectsPrivateFilesAndEmbeddedIntentsWithVisibleFailure() {
        val payload = IncomingContentParser.fromIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/private"))
        )!!
        assertTrue(payload.isEmpty)
        assertEquals(1, payload.skipped)
        val embedded = IncomingContentParser.fromClipData(ClipData.newIntent("not data", Intent("danger")))!!
        assertTrue(embedded.isEmpty)
        assertEquals(1, embedded.skipped)
    }

    @Test fun malformedExtrasDoNotHideValidClipData() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a uri")
        intent.clipData = ClipData.newPlainText("fallback", "still readable")
        val payload = IncomingContentParser.fromIntent(intent)!!
        assertEquals("still readable", payload.text)
        assertFalse(payload.isEmpty)
        assertTrue(payload.skipped > 0)
    }
}
