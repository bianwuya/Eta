package io.github.mangi.eta.local.imagegen

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID

/** Publish a validated image using the standard Downloads API, without broader file permissions. */
internal object ImageGenOutput {
    private const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4
    data class Saved(val uri: String, val location: String, val mime: String, val width: Int, val height: Int)

    fun save(context: Context, encoded: String, controller: AgentRunController): Saved {
        controller.throwIfCancelled()
        require(encoded.length in 1..MAX_BASE64)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 1..MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L)
        val mime = bounds.outMimeType
        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> error("Unsupported image format")
        }
        val sample = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)).recycle()
        controller.throwIfCancelled()
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/Eta/Generated"
        val name = "eta-${UUID.randomUUID()}.$extension"
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            controller.throwIfCancelled()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            published = true
            return Saved(uri.toString(), "$directory/$name", mime, bounds.outWidth, bounds.outHeight)
        } finally {
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
