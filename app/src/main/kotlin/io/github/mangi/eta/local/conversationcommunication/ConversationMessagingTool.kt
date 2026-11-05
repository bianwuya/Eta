package io.github.mangi.eta.local.conversationcommunication

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * 两个跨会话工具的执行入口。参数错误返回结构化错误码，不做静默兜底；
 * 投递语义完全由 [ConversationMessagingService] 决定。
 */
internal class ConversationMessagingTool(
    private val service: ConversationMessagingService,
) : AgentModelClient.ToolExecutor {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val name = toolCall.name
        if (name !in ConversationMessagingToolCatalog.names) {
            return result(error("UNKNOWN_TOOL", "未知工具：$name"))
        }
        val args = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return result(error("INVALID_ARGUMENT", "工具参数不是合法 JSON")) }
        val output = when (name) {
            ConversationMessagingService.TOOL_LIST -> {
                val limit = if (args.has("limit")) args.optInt("limit", 0) else DEFAULT_LIST
                if (limit !in 1..ConversationMessagingService.MAX_LIST) {
                    return result(error("INVALID_ARGUMENT", "limit 必须在 1–${ConversationMessagingService.MAX_LIST}"))
                }
                val query = args.optString("query")
                if (query.length > MAX_QUERY) {
                    return result(error("INVALID_ARGUMENT", "query 不能超过 $MAX_QUERY 字符"))
                }
                service.list(query, limit)
            }
            else -> service.send(
                targetConversationId = args.optString("conversation_id"),
                text = args.optString("message"),
                requestedMessageId = args.optString("message_id"),
            )
        }
        return result(output)
    }

    private fun result(content: String): AgentModelClient.ToolResult = AgentModelClient.ToolResult(content)

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    private companion object {
        const val DEFAULT_LIST = 10
        const val MAX_QUERY = 200
    }
}
