package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Pending input belongs to the conversation transaction, separate from model history.
 * Images retain their original bytes in the existing chunk store, never a temporary URI.
 * File/directory references keep their meaning; cache files are imported into durable storage.
 */
internal object AgentPendingQueueCodec {
    private const val FIELD = "pending_queue_v1"
    private const val VOICE_OWNER_PREFIX = "voice-steering:"

    fun voiceOwner(conversationId: String): String = VOICE_OWNER_PREFIX + conversationId

    fun encode(context: Context, queue: List<QueuedMessageUi>, previous: List<QueuedMessageUi> = emptyList()): String {
        val previousImages = previous.flatMap { it.pendingImages }.associateBy { it.id }
        val previousFiles = previous.flatMap { it.pendingFileReferences }.associateBy { it.id }
        val cache = context.cacheDir.canonicalFile.toPath()
        val rows = JSONArray()
        queue.forEach { item ->
            rows.put(JSONObject().put("id", item.id).put("text", item.content)
                .put("mode", item.requestMode).put("interrupt", item.immediateInterrupt)
                .put("run", item.steeringRunId).put("unknown", item.steeringUnconfirmed)
                .put("images", JSONArray().also { images -> item.pendingImages.forEach { image ->
                    val stable = previousImages[image.id]?.uri ?: stableImage(context, image)
                    images.put(JSONObject().put("id", image.id).put("original", stable)
                        .put("preview", image.dataUrl).put("mime", image.mimeType))
                } })
                .put("files", JSONArray().also { files -> item.pendingFileReferences.forEach { file ->
                    val reference = file.reference
                    val path = File(reference.absolutePath)
                    val stable = previousFiles[file.id]?.reference ?: if (path.canonicalFile.toPath().startsWith(cache)) {
                        require(reference.kind == AgentFileReferenceKind.File) { "临时目录不能加入持久队列" }
                        when (val imported = io.github.mangi.eta.agent.device.AgentFileReferenceGateway.importDocumentUri(context, Uri.fromFile(path))) {
                            is io.github.mangi.eta.agent.device.AgentFileReferenceGateway.Resolution.Success -> imported.reference
                            is io.github.mangi.eta.agent.device.AgentFileReferenceGateway.Resolution.Failure -> error("无法保存队列文件")
                        }
                    } else reference
                    files.put(JSONObject().put("id", file.id).put("name", reference.displayName)
                        .put("path", stable.absolutePath).put("kind", reference.kind.name))
                } }))
        }
        return JSONObject().put("version", 1).put("items", rows).toString()
    }

    private fun stableImage(context: Context, image: PendingImageUi): String {
        val value = image.uri
        if (value.startsWith("data:image/") || value.startsWith("https://") || value.startsWith("http://")) return value
        val uri = Uri.parse(value)
        val input = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri) ?: error("无法读取队列图片")
            "file" -> File(requireNotNull(uri.path)).inputStream()
            else -> File(value).inputStream()
        }
        val bytes = input.use { it.readNBytes(MAX_AGENT_IMAGE_BYTES + 1) }
        require(bytes.isNotEmpty() && bytes.size <= MAX_AGENT_IMAGE_BYTES) { "队列图片为空或过大" }
        val original = AgentImageCodec.fromBytes(bytes, "user_attach", image.mimeType)
        require(original.width != null && original.height != null) { "队列图片格式无效" }
        return original.reference
    }

    fun decode(raw: String): List<QueuedMessageUi> {
        if (raw.isEmpty()) return emptyList()
        val document = JSONObject(raw)
        require(document.getInt("version") == 1) { "不支持的队列版本" }
        val rows = document.getJSONArray("items")
        val ids = mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            require(id.isNotBlank() && ids.add(id)) { "队列标识重复或缺失" }
            val mode = row.getString("mode")
            require(mode == "normal" || mode == "plan") { "队列模式无效" }
            val images = row.getJSONArray("images")
            val files = row.getJSONArray("files")
            val run = row.optString("run")
            QueuedMessageUi(id, row.getString("text"),
                pendingImages = (0 until images.length()).map { i -> images.getJSONObject(i).let { image ->
                    PendingImageUi(image.getString("id"), image.getString("original"), image.getString("preview"),
                        image.getString("mime"), displayUrl = image.getString("original"))
                } },
                pendingFileReferences = (0 until files.length()).map { i -> files.getJSONObject(i).let { file ->
                    PendingFileReferenceUi(file.getString("id"), AgentFileReference(file.getString("name"),
                        file.getString("path"), AgentFileReferenceKind.valueOf(file.getString("kind"))))
                } },
                immediateInterrupt = false, steeringRunId = run,
                steeringUnconfirmed = run.isNotBlank() || row.optBoolean("unknown"),
                requestMode = mode, restoredFromStorage = true,
            )
        }
    }

    suspend fun write(dao: ConversationDao, id: String, raw: String) {
        dao.deleteTextChunks("conversations", id, FIELD)
        var start = 0
        var index = 0
        while (start < raw.length) {
            var end = minOf(start + 16_384, raw.length)
            if (end < raw.length && raw[end - 1].isHighSurrogate()) end--
            dao.insertTextChunk(AgentTextChunkEntity("conversations", id, FIELD, index++, raw.substring(start, end)))
            start = end
        }
    }

    suspend fun read(dao: ConversationDao, id: String): List<QueuedMessageUi> {
        val own = readOwned(dao, id)
        if (id.startsWith(VOICE_OWNER_PREFIX)) return own
        val voice = readOwned(dao, voiceOwner(id))
        return (own + voice).distinctBy { it.id }
    }

    suspend fun readOwned(dao: ConversationDao, id: String): List<QueuedMessageUi> {
        val raw = StringBuilder()
        var offset = 0
        while (true) {
            val page = dao.textChunks("conversations", id, FIELD, 32, offset)
            if (page.isEmpty()) break
            page.forEach { require(it.chunkIndex == offset++) { "队列内容不完整" }; raw.append(it.content) }
        }
        return decode(raw.toString())
    }

    suspend fun acknowledgeVoice(context: Context, dao: ConversationDao, conversationId: String, queueId: String) {
        val owner = voiceOwner(conversationId)
        val pending = readOwned(dao, owner)
        if (pending.any { it.id == queueId }) {
            val remaining = pending.filterNot { it.id == queueId }
            if (remaining.isEmpty()) dao.deleteTextChunks("conversations", owner, FIELD)
            else {
                write(dao, owner, encode(context, remaining))
            }
        }
    }
}
