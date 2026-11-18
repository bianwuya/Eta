package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Compatible with the personal build's per-conversation queue; never rewrite the separate voice owner. */
internal object AgentPendingQueueCodec {
    private const val TABLE = "conversations"
    internal const val FIELD = "pending_queue_v1"
    private const val CHUNK_CHARS = 16_384

    fun encode(queue: List<QueuedMessageUi>): String = JSONObject()
        .put("version", 1)
        .put("items", JSONArray().apply {
            queue.forEach { item ->
                put(JSONObject().put("id", item.id).put("text", item.content).put("mode", "normal")
                    .put("interrupt", item.immediateInterrupt).put("run", item.steeringRunId)
                    .put("unknown", item.awaitingSteeringReceipt)
                    .put("images", JSONArray().apply {
                        item.pendingImages.forEach { image ->
                            put(JSONObject().put("id", image.id).put("original", image.uri)
                                .put("preview", image.dataUrl.takeUnless { it == image.uri }.orEmpty()).put("mime", image.mimeType))
                        }
                    }).put("files", JSONArray().apply {
                        item.pendingFileReferences.forEach { file ->
                            put(JSONObject().put("id", file.id).put("name", file.reference.displayName)
                                .put("path", file.reference.absolutePath).put("kind", file.reference.kind.name))
                        }
                    }))
            }
        }).toString()

    fun decode(raw: String): List<QueuedMessageUi> {
        if (raw.isEmpty()) return emptyList()
        val document = JSONObject(raw)
        require(document.getInt("version") == 1) { "Unsupported pending queue version" }
        val ids = hashSetOf<String>()
        return document.getJSONArray("items").mapObjects { row ->
            val id = row.getString("id")
            require(id.isNotBlank() && ids.add(id)) { "Invalid pending queue identity" }
            require(row.getString("mode") in setOf("normal", "plan")) { "Invalid pending queue mode" }
            val run = row.optString("run")
            QueuedMessageUi(
                id = id,
                content = row.getString("text"),
                pendingImages = row.getJSONArray("images").mapObjects { image ->
                    val original = image.getString("original")
                    PendingImageUi(image.getString("id"), original, image.optString("preview").ifBlank { original }, image.getString("mime"))
                },
                pendingFileReferences = row.getJSONArray("files").mapObjects { file ->
                    PendingFileReferenceUi(file.getString("id"), AgentFileReference(file.getString("name"), file.getString("path"),
                        AgentFileReferenceKind.valueOf(file.getString("kind"))))
                },
                // Do not re-issue an old promotion or a request with an unknown outcome after restart.
                steeringRunId = run,
                steeringUnconfirmed = run.isNotBlank() || row.optBoolean("unknown"),
            )
        }
    }

    private inline fun <T> JSONArray.mapObjects(map: (JSONObject) -> T): List<T> = List(length()) { map(getJSONObject(it)) }

    suspend fun read(dao: ConversationDao, owner: String): List<QueuedMessageUi> {
        val raw = StringBuilder()
        var offset = 0
        while (true) {
            val page = dao.textChunks(TABLE, owner, FIELD, 32, offset)
            if (page.isEmpty()) break
            page.forEach { chunk ->
                check(chunk.chunkIndex == offset++) { "Incomplete pending queue" }
                raw.append(chunk.content)
            }
        }
        return decode(raw.toString())
    }

    /** Caller includes this and the corresponding history/user message changes in one Room transaction. */
    suspend fun write(context: Context, dao: ConversationDao, owner: String, queue: List<QueuedMessageUi>) {
        val raw = if (queue.isEmpty()) "" else encode(durableAttachments(context, queue, read(dao, owner)))
        dao.deleteTextChunks(TABLE, owner, FIELD)
        var start = 0
        var index = 0
        while (start < raw.length) {
            var end = minOf(start + CHUNK_CHARS, raw.length)
            if (end < raw.length && raw[end - 1].isHighSurrogate()) end--
            dao.insertTextChunk(AgentTextChunkEntity(TABLE, owner, FIELD, index++, raw.substring(start, end)))
            start = end
        }
    }

    private fun durableAttachments(context: Context, queue: List<QueuedMessageUi>, previous: List<QueuedMessageUi>): List<QueuedMessageUi> {
        val images = previous.flatMap { it.pendingImages }.associateBy { it.id }
        val files = previous.flatMap { it.pendingFileReferences }.associateBy { it.id }
        val cache = context.cacheDir.canonicalFile.toPath()
        return queue.map { item ->
            item.copy(
                pendingImages = item.pendingImages.map { image ->
                    val ref = image.uri
                    if (ref.startsWith("data:image/", true) || ref.startsWith("https://", true) || ref.startsWith("http://", true)) image
                    else images[image.id] ?: run {
                        val decoded = AgentImageCodec.fromReference(context, ref, "user_attach", preserveOriginal = true)
                        requireNotNull(decoded?.takeIf { it.width != null && it.height != null }) { "Cannot preserve pending image" }
                        image.copy(uri = decoded.reference, mimeType = decoded.mimeType)
                    }
                },
                pendingFileReferences = item.pendingFileReferences.map { file ->
                    val source = File(file.reference.absolutePath)
                    if (!source.canonicalFile.toPath().startsWith(cache)) file
                    else files[file.id] ?: run {
                        require(file.reference.kind == AgentFileReferenceKind.File) { "Cannot preserve a temporary directory" }
                        val imported = AgentFileReferenceGateway.importDocumentUri(context, Uri.fromFile(source))
                        check(imported is AgentFileReferenceGateway.Resolution.Success) { "Cannot preserve pending attachment" }
                        file.copy(reference = imported.reference.copy(displayName = file.reference.displayName))
                    }
                },
            )
        }
    }
}
