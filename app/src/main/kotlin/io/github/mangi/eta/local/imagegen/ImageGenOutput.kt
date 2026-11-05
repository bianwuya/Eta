package io.github.mangi.eta.local.imagegen

import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal object ImageGenOutput {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4
    data class Saved(val file: File, val mime: String, val width: Int, val height: Int)

    fun save(encoded: String, controller: AgentRunController,
        directory: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Eta-输出/生成图片")): Saved {
        require(encoded.isNotBlank() && encoded.length <= MAX_BASE64) { "图片为空或超过32MiB限制" }
        controller.throwIfCancelled()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "图片为空或超过32MiB限制" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "返回内容不是有效尺寸的图片" }
        val sample = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample) ?: error("返回图片解码失败")
        decoded.recycle()
        val extension = when (options.outMimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> error("返回的图片格式不支持")
        }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建图片输出目录" }
        val stem = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val temporary = File(directory, ".$stem.part")
        val target = File(directory, "$stem.$extension")
        try {
            controller.throwIfCancelled()
            FileOutputStream(temporary).use { out -> out.write(bytes); out.fd.sync() }
            controller.throwIfCancelled()
            check(temporary.length() == bytes.size.toLong() && temporary.renameTo(target)) { "图片保存失败" }
            return Saved(target, options.outMimeType, options.outWidth, options.outHeight)
        } finally { temporary.delete() }
    }
}
