package io.github.mangi.eta.local.incoming

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway.Resolution
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

internal object IncomingContentInbox {
    private const val MAX_ITEMS = 20
    private const val TOTAL_LIMIT = 64L * 1024 * 1024
    private const val IMAGE_LIMIT = 12L * 1024 * 1024
    private val state = MutableStateFlow(0L)
    val changes: StateFlow<Long> = state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(
        context: Context,
        payload: IncomingPayload,
        id: String = UUID.randomUUID().toString(),
    ): IncomingContent? = withContext(Dispatchers.IO) {
        if (payload.isEmpty && payload.skipped == 0) return@withContext null
        val app = context.applicationContext
        val root = File(app.filesDir, "local-inbox")
        val ownedImports = mutableListOf<File>()
        val jobContext = coroutineContext
        synchronized(this@IncomingContentInbox) {
            val existing = read(root)
            existing.firstOrNull { it.id == id }?.let { return@withContext it }
            if (existing.size >= MAX_ITEMS) throw IOException("inbox full")
        }
        var completed = false
        try {
            var totalBytes = 0L
            var skipped = payload.skipped
            val images = mutableListOf<PendingImageUi>()
            val files = mutableListOf<PendingFileReferenceUi>()
            for (uri in payload.uris.take(20)) {
                coroutineContext.ensureActive()
                try {
                    when (val result = AgentFileReferenceGateway.importDocumentUri(app, uri)) {
                        is Resolution.Success -> {
                            val reference = result.reference
                            val file = File(reference.absolutePath)
                            file.parentFile?.let(ownedImports::add)
                            val length = file.length()
                            if (length <= 0 || totalBytes + length > TOTAL_LIMIT) {
                                file.parentFile?.deleteRecursively()
                                skipped++
                                continue
                            }
                            totalBytes += length
                            val resolverMime = try { app.contentResolver.getType(uri).orEmpty() } catch (_: Exception) { "" }
                            val mime = resolverMime.ifBlank { mimeFromName(reference.displayName) }
                                .ifBlank { payload.mimeType.orEmpty() }
                            if (!mime.startsWith("image/", ignoreCase = true) || length > IMAGE_LIMIT) {
                                files += PendingFileReferenceUi(UUID.randomUUID().toString(), reference)
                                if (mime.startsWith("image/", ignoreCase = true)) skipped++
                                continue
                            }
                            val image = AgentImageCodec.fromTransferReference(app, reference.absolutePath, "user_share")
                            if (image == null || image.width == null || image.width <= 0 || image.height == null || image.height <= 0) {
                                files += PendingFileReferenceUi(UUID.randomUUID().toString(), reference)
                                skipped++
                                continue
                            }
                            val preview = AgentImageCodec.previewFromReference(app, image)
                            images += PendingImageUi(
                                UUID.randomUUID().toString(), reference.absolutePath,
                                preview?.reference ?: reference.absolutePath, image.mimeType,
                            )
                        }
                        is Resolution.Failure -> skipped++
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    skipped++
                }
            }
            coroutineContext.ensureActive()
            val content = IncomingContent(id, payload.text.take(100_000), images, files, skipped)
            if (content.text.isBlank() && images.isEmpty() && files.isEmpty() && content.skipped == 0) return@withContext null
            synchronized(this@IncomingContentInbox) {
                val current = read(root)
                current.firstOrNull { it.id == id }?.let { return@withContext it }
                if (current.size >= MAX_ITEMS) throw IOException("inbox full")
                jobContext.ensureActive()
                write(root, current + content)
                state.value += 1
            }
            completed = true
            content
        } finally {
            // Only directories returned by this import belong to us. Other shares can import concurrently.
            if (!completed) ownedImports.forEach { it.deleteRecursively() }
        }
    }

    fun list(context: Context): List<IncomingContent> = synchronized(this) {
        read(File(context.applicationContext.filesDir, "local-inbox"))
    }

    fun remove(context: Context, id: String) = synchronized(this) {
        val root = File(context.applicationContext.filesDir, "local-inbox")
        val current = read(root)
        val next = current.filterNot { it.id == id }
        if (next.size != current.size) {
            write(root, next)
            state.value += 1
        }
    }

    private fun read(root: File): List<IncomingContent> {
        val target = File(root, "queue.json")
        val atomic = AtomicFile(target)
        if (!target.exists() && !File(target.path + ".bak").exists()) return emptyList()
        val text = try { atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }
        catch (e: Exception) { throw IOException("Unable to read inbox", e) }
        return try {
            json.parseToJsonElement(text).jsonArray.map { decode(it) }.also {
                if (it.size > MAX_ITEMS) throw IOException("Invalid inbox size")
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Corrupt inbox metadata", e) }
    }

    private fun write(root: File, items: List<IncomingContent>) {
        root.mkdirs()
        val atomic = AtomicFile(File(root, "queue.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            val bytes = buildJsonArray { items.forEach { add(encode(it)) } }.toString().toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw e
        }
    }

    private fun encode(content: IncomingContent) = buildJsonObject {
        put("id", JsonPrimitive(content.id))
        put("text", JsonPrimitive(content.text))
        put("skipped", JsonPrimitive(content.skipped))
        put("images", buildJsonArray {
            content.images.forEach { image -> add(buildJsonObject {
                put("id", JsonPrimitive(image.id)); put("uri", JsonPrimitive(image.uri))
                put("dataUrl", JsonPrimitive(image.dataUrl)); put("mime", JsonPrimitive(image.mimeType))
            }) }
        })
        put("files", buildJsonArray {
            content.files.forEach { file -> add(buildJsonObject {
                put("id", JsonPrimitive(file.id)); put("name", JsonPrimitive(file.reference.displayName))
                put("path", JsonPrimitive(file.reference.absolutePath)); put("kind", JsonPrimitive(file.reference.kind.name))
            }) }
        })
    }

    private fun decode(element: kotlinx.serialization.json.JsonElement): IncomingContent {
        val obj = element.jsonObject
        return IncomingContent(
            obj.getValue("id").jsonPrimitive.content,
            obj.getValue("text").jsonPrimitive.content,
            obj["images"]?.jsonArray?.map { item ->
                val image = item.jsonObject
                PendingImageUi(image.getValue("id").jsonPrimitive.content, image.getValue("uri").jsonPrimitive.content,
                    image.getValue("dataUrl").jsonPrimitive.content, image.getValue("mime").jsonPrimitive.content)
            }.orEmpty(),
            obj["files"]?.jsonArray?.map { item ->
                val file = item.jsonObject
                PendingFileReferenceUi(file.getValue("id").jsonPrimitive.content, AgentFileReference(
                    file.getValue("name").jsonPrimitive.content, file.getValue("path").jsonPrimitive.content,
                    AgentFileReferenceKind.valueOf(file.getValue("kind").jsonPrimitive.content)))
            }.orEmpty(),
            obj["skipped"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        else -> ""
    }
}
