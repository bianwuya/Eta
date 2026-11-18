package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

internal object ImageGenToolCatalog {
    const val TOOL_NAME = "generate_image"
    const val MAX_PROMPT_LENGTH = 16_000

    fun schema(): JSONObject = AgentToolSchema.function(
        TOOL_NAME,
        "仅在用户要求时生成一张图片。固定使用设置中的独立生图连接与模型，与聊天模型无关。" +
            "可能消耗额度；outcome_unknown 时不得自动重试或走其他来源。成功时告知用户下载位置（location）。",
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put("prompt", JSONObject().put("type", "string")
                .put("minLength", 1).put("maxLength", MAX_PROMPT_LENGTH)))
            .put("required", JSONArray().put("prompt"))
            .put("additionalProperties", false),
    )
}

/** A run-local tool. Replayed call IDs must never send another paid POST. */
internal class ImageGenTool(
    private val connection: () -> ImageGenConnection?,
    private val generate: (String, AgentRunController, ImageGenConnection) -> JSONObject,
) : AutoCloseable {
    constructor(context: Context) : this(
        connection = { ImageGenConfigStore(context).connection() },
        generate = { prompt, controller, connection ->
            ImageGenClient({ encoded, run -> ImageGenOutput.save(context, encoded, run) })
                .generate(prompt, controller, connection)
        },
    )

    private val controller = AgentRunController()
    private val receipts = mutableMapOf<String, AgentModelClient.ToolResult>()

    override fun close() = controller.cancel()

    @Synchronized
    fun execute(callId: String, arguments: JSONObject): AgentModelClient.ToolResult {
        controller.throwIfCancelled()
        receipts[callId]?.let { return it }
        if (callId.isBlank() || receipts.size >= 64) {
            return result(imageGenFailure("IMAGE_GEN_CALL_LIMIT", "本次生图调用标识无效或已达上限，未执行。"))
        }
        val prompt = arguments.opt("prompt") as? String
        if (prompt.isNullOrBlank() || prompt.length > ImageGenToolCatalog.MAX_PROMPT_LENGTH || arguments.length() != 1) {
            return result(imageGenFailure("IMAGE_GEN_INVALID_ARGUMENT", "只接受非空图片描述 prompt，不能指定连接、账号或模型。"))
        }
        // Re-read local authorization; an already-advertised tool may run after removal or with a missing key.
        val configured = try { connection() } catch (_: Exception) { null }
            ?: return result(imageGenFailure("IMAGE_GEN_NOT_CONFIGURED", "请先在设置中保存可用的独立生图连接和模型。"))
        controller.throwIfCancelled()
        val uncertain = result(imageGenFailure("IMAGE_GEN_OUTCOME_UNKNOWN", "生图结果未确认，未重复执行，请先核对下载目录和额度。", true))
        receipts[callId] = uncertain
        return try {
            result(generate(prompt, controller, configured)).also { receipts[callId] = it }
        } catch (_: Exception) {
            controller.throwIfCancelled()
            uncertain
        }
    }

    private fun result(payload: JSONObject) = AgentModelClient.ToolResult(payload.toString())
}
