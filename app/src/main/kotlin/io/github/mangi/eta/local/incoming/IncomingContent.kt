package io.github.mangi.eta.local.incoming

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.Html
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi

internal data class IncomingPayload(
    val text: String,
    val uris: List<Uri>,
    val mimeType: String? = null,
    val skipped: Int = 0,
) {
    val isEmpty: Boolean get() = text.isBlank() && uris.isEmpty()
}

internal data class IncomingContent(
    val id: String,
    val text: String,
    val images: List<PendingImageUi> = emptyList(),
    val files: List<PendingFileReferenceUi> = emptyList(),
    val skipped: Int = 0,
)

/** Untrusted share metadata is plain data, never an embedded Intent or a path to execute. */
internal object IncomingContentParser {
    private const val MAX_URIS = 20
    private const val MAX_TEXT = 100_000
    private const val MAX_CLIP_ITEMS = 100

    fun fromIntent(intent: Intent): IncomingPayload? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val collector = Collector()
        try {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) collector.addText(text)
            else intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { collector.addHtml(it) }
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                    val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    if (streams == null) collector.skipped++ else {
                        streams.take(MAX_CLIP_ITEMS).forEach { collector.addUri(it) }
                        collector.skipped += (streams.size - MAX_CLIP_ITEMS).coerceAtLeast(0)
                    }
                } else {
                    collector.addUri(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                }
            }
        } catch (_: RuntimeException) {
            collector.skipped++
        }
        // A malformed extra must not hide a valid ClipData fallback.
        try { intent.clipData?.let { collector.addClip(it) } }
        catch (_: RuntimeException) { collector.skipped++ }
        return collector.build(intent.type)
    }

    fun fromClipData(clipData: ClipData): IncomingPayload? {
        val collector = Collector()
        collector.addClip(clipData)
        val mime = runCatching { clipData.description.getMimeType(0) }.getOrNull()
        return collector.build(mime)
    }

    private class Collector {
        private val texts = linkedSetOf<String>()
        private val uris = linkedSetOf<Uri>()
        var skipped = 0

        fun addText(raw: String) {
            val limited = raw.take(MAX_TEXT)
            if (limited.length != raw.length) skipped++
            limited.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
        }

        fun addHtml(raw: String) {
            val limited = raw.take(MAX_TEXT * 4)
            if (limited.length != raw.length) skipped++
            addText(Html.fromHtml(limited, Html.FROM_HTML_MODE_LEGACY).toString())
        }

        fun addUri(uri: Uri?) {
            when (uri?.scheme?.lowercase()) {
                "content" -> uris.add(uri)
                "http", "https" -> addText(uri.toString())
                // Do not allow a share sender to read Eta's own private files via file://.
                else -> skipped++
            }
        }

        fun addClip(clip: ClipData) {
            val count = minOf(clip.itemCount, MAX_CLIP_ITEMS)
            skipped += clip.itemCount - count
            repeat(count) { index ->
                try {
                    val item = clip.getItemAt(index)
                    val text = item.text?.toString()
                    val html = item.htmlText
                    if (!text.isNullOrBlank()) addText(text)
                    else if (!html.isNullOrBlank()) addHtml(html)
                    item.uri?.let { addUri(it) }
                    if (text.isNullOrBlank() && html.isNullOrBlank() && item.uri == null) skipped++
                    // Embedded intents are deliberately ignored, never launched or coerced to text.
                } catch (_: RuntimeException) {
                    skipped++
                }
            }
        }

        fun build(mime: String?): IncomingPayload? {
            val joined = texts.joinToString("\n")
            if (joined.length > MAX_TEXT) skipped++
            val selected = uris.take(MAX_URIS)
            skipped += uris.size - selected.size
            val result = IncomingPayload(joined.take(MAX_TEXT), selected, mime, skipped)
            return result.takeUnless { it.isEmpty && it.skipped == 0 }
        }
    }
}
