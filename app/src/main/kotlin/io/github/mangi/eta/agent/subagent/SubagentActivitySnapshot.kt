package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

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
            currentAction = currentAction.take(limit),
            steps = recentSteps.map { step ->
                step.copy(
                    id = step.id.take(limit),
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

        // Account for JSON escaping and structural overhead, including many short steps.
        while (preview(0).encode().length > maxChars && recentSteps.isNotEmpty()) {
            recentSteps = recentSteps.drop(1)
        }
        var best = preview(0).encode()
        require(best.length <= maxChars)
        var low = 1
        var high = maxChars
        while (low <= high) {
            val limit = low + (high - low) / 2
            val candidate = preview(limit).encode()
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

        private const val SNAPSHOT_PREFIX = "{\"subagentId\":"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
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
