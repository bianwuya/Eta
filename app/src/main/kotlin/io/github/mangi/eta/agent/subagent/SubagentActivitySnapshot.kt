package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Last actual report, not a recomputed size of the current request. */
@Serializable
internal data class SubagentContextReading(
    val tokens: Int,
    val source: String = SOURCE_USAGE,
    val at: Long = 0,
    val beforeCompaction: Boolean = false,
    val previousInvocation: Boolean = false,
    val modelChanged: Boolean? = null,
    // A reading may arrive before a request fails; never label it a successful report by default.
    val attempt: String = ATTEMPT_UNKNOWN,
) {
    companion object {
        const val SOURCE_USAGE = "usage"
        const val SOURCE_COMPACTION = "compaction"
        const val ATTEMPT_UNKNOWN = "unknown"
        const val ATTEMPT_REPORTED = "reported"
        const val ATTEMPT_COMPLETED = "completed"
        const val ATTEMPT_FAILED = "failed"
        const val ATTEMPT_CANCELLED = "cancelled"
    }
}

@Serializable
internal data class SubagentStepSnapshot(
    val id: String,
    val kind: String,
    val title: String = "",
    val detail: String = "",
    val status: String = STATUS_RUNNING,
    val toolName: String = "",
    val detailTruncated: Boolean = false,
) {
    companion object {
        const val KIND_THINKING = "thinking"
        const val KIND_TOOL = "tool"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Subagent 实时与持久化的结构化活动快照。
 *
 * 保存在父级 `subagent` 工具消息的 `command` 字段中，既支持运行中点击实时检视，
 * 也支持会话归档与重启后直接点开回看完整内部过程。
 */
@Serializable
internal data class SubagentActivitySnapshot(
    val subagentId: String = "",
    val subagentType: String = SubagentProfile.DEFAULT_TYPE,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    val resumed: Boolean = false,
    val status: String = STATUS_RUNNING,
    val currentAction: String = "",
    val steps: List<SubagentStepSnapshot> = emptyList(),
    val output: String = "",
    val error: String? = null,
    val detailId: String = "",
    val previewTruncated: Boolean = false,
    val totalSteps: Int = steps.size,
    val updatedAt: Long = 0,
    // Current invocation's most recent report, null after compaction or before a resumed report.
    // Legacy snapshots may have this value without contextReading metadata.
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
    val compactionCount: Int? = null,
    val contextReading: SubagentContextReading? = null,
    val contextRequestState: String = REQUEST_UNKNOWN,
) {
    val toolStepCount: Int
        get() = steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }

    fun encode(): String = json.encodeToString(this)

    /** Bound the display payload before serialization so transport never cuts a JSON token. */
    fun encodeBounded(maxChars: Int): String {
        val encoded = encode()
        if (encoded.length <= maxChars) return encoded

        require(maxChars >= 512)
        var recentSteps = steps.takeLast(48)
        fun preview(limit: Int): SubagentActivitySnapshot = copy(
            previewTruncated = true,
            description = description.take(limit),
            prompt = prompt.take(limit),
            model = model.takeIf { it.length <= limit }.orEmpty(),
            currentAction = currentAction.take(limit),
            steps = recentSteps.map { step ->
                step.copy(
                    // IDs are display identity, not visible text. Prefix truncation can merge
                    // distinct provider IDs and crash the inspector's LazyColumn.
                    id = if (step.id.length <= limit) step.id else step.id.take(32) + "~" +
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(step.id.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) },
                    kind = step.kind.take(limit),
                    title = step.title.take(limit),
                    detail = step.detail.takeLast(limit),
                    detailTruncated = step.detailTruncated || step.detail.length > limit,
                    toolName = step.toolName.take(limit),
                )
            },
            output = output.takeLast(limit),
            error = error?.take(limit),
        )

        // Omitting default-valued fields gives metadata room even for the smallest preview;
        // decoding restores the same defaults, including for legacy snapshots.
        fun encodePreview(limit: Int) = compactJson.encodeToString(preview(limit))
        // Account for JSON escaping and structural overhead, including many short steps.
        while (encodePreview(0).length > maxChars && recentSteps.isNotEmpty()) {
            recentSteps = recentSteps.drop(1)
        }
        var best = encodePreview(0)
        require(best.length <= maxChars)
        var low = 1
        var high = maxChars
        while (low <= high) {
            val limit = low + (high - low) / 2
            val candidate = encodePreview(limit)
            if (candidate.length <= maxChars) {
                best = candidate
                low = limit + 1
            } else {
                high = limit - 1
            }
        }
        return best
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        const val REQUEST_UNKNOWN = "unknown"
        const val REQUEST_IN_PROGRESS = "running"
        const val REQUEST_RETRYING = "retrying"
        const val REQUEST_COMPLETED = "completed"
        const val REQUEST_FAILED = "failed"
        const val REQUEST_CANCELLED = "cancelled"

        private const val SNAPSHOT_PREFIX = "{\"subagentId\":"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val compactJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun decode(raw: String?): SubagentActivitySnapshot? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString<SubagentActivitySnapshot>(raw) }
                .getOrNull()
                ?.takeIf { it.subagentType.isNotBlank() || it.description.isNotBlank() || it.prompt.isNotBlank() }
        }

        /**
         * A launch or resume that never reached the model must not leave the initial
         * "running / 正在启动" card behind. `updatedAt` is real time so this snapshot wins over the
         * synthetic launch preview when the tool result is projected.
         */
        fun failureFromArguments(
            argumentsJson: String,
            message: String,
            status: String = STATUS_FAILED,
            now: Long = System.currentTimeMillis(),
        ): SubagentActivitySnapshot =
            (initialFromArguments(argumentsJson) ?: SubagentActivitySnapshot(subagentType = SUBAGENT_FALLBACK_TYPE))
                .copy(
                    status = status,
                    currentAction = message.lineSequence().firstOrNull()?.take(120).orEmpty(),
                    error = message.take(1_000),
                    updatedAt = now,
                )

        private const val SUBAGENT_FALLBACK_TYPE = "subagent"

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim()
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .take(80)
            val fullPrompt = args.optString("prompt").trim()
            val prompt = fullPrompt.take(4_000)
            SubagentActivitySnapshot(
                previewTruncated = fullPrompt.length > prompt.length,
                subagentId = resumeId,
                subagentType = type,
                description = description,
                prompt = prompt,
                resumed = resumeId.isNotEmpty(),
                status = STATUS_RUNNING,
                currentAction = "正在启动…",
            )
        }.getOrNull()
    }
}
