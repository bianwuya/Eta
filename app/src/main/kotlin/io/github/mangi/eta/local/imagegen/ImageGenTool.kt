package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

internal object ImageGenToolCatalog {
    const val TOOL_NAME = "generate_image"
    fun schema(): JSONObject = AgentToolSchema.function(TOOL_NAME,
        "按用户要求生成一张真实图片，使用设置中固定的生图来源、账号与模型，不随聊天选择变化。" +
            "会消耗所选来源额度；未配置先让用户配置。成功返回已保存的本地图片引用，用markdown展示。" +
            "outcome_unknown表示可能已扣额，不得自动重试或改走其他账号/API；不要用程序绘图冒充。",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("prompt", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 16000))
            .put("size", JSONObject().put("type", "string").put("enum", JSONArray(ImageGenConfig.SIZES))
                .put("description", "可选，仅覆盖本张尺寸；不改变固定来源、账号或模型")))
            .put("required", JSONArray().put("prompt")))
}

/** Per run-local executor: identical tool-call IDs never send a second paid generation. */
internal class ImageGenTool(
    private val context: Context,
    private val controller: () -> AgentRunController,
    private val generate: (String, AgentRunController, ImageGenConfig) -> JSONObject = ImageGenClient(context)::generate,
) {
    private val receipts = linkedMapOf<String, AgentModelClient.ToolResult>()

    @Synchronized
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        receipts[call.id]?.let { return it }
        if (call.id.isBlank() || receipts.size >= 64) return AgentModelClient.ToolResult(
            ImageGenClient.failure("IMAGE_GEN_CALL_LIMIT", "本任务生图调用标识无效或已达上限，请勿重复发起").toString())
        // Write the uncertainty receipt before any request, including before a cancellation can unwind it.
        receipts[call.id] = AgentModelClient.ToolResult(ImageGenClient.failure("IMAGE_GEN_OUTCOME_UNKNOWN",
            "本次生图已开始但结果未确认，未重复执行，请核对输出和额度后再决定。", unknown = true).toString())
        val run = controller()
        val result = try {
            run.throwIfCancelled()
            val args = JSONObject(call.argumentsJson)
            val settings = ImageGenConfigStore.read(context)
            val config = if (args.has("size")) settings.copy(size = args.getString("size")) else settings
            generate(args.optString("prompt"), run, config)
        } catch (failure: Exception) {
            run.throwIfCancelled()
            ImageGenClient.failure("IMAGE_GEN_FAILED", "图片配置、请求或保存失败，未自动重试。请检查配置和输出目录。", unknown = true)
        }
        return AgentModelClient.ToolResult(result.toString()).also { receipts[call.id] = it }
    }
}
