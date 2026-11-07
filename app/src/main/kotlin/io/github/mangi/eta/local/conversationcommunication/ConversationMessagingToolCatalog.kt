package io.github.mangi.eta.local.conversationcommunication

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/** 跨会话工具目录：只用于主会话 run，不发布给子代理。 */
internal object ConversationMessagingToolCatalog {
    val names: Set<String> = setOf(
        ConversationMessagingService.TOOL_LIST,
        ConversationMessagingService.TOOL_SEND,
    )

    fun appendSchemas(tools: JSONArray) {
        tools.put(schema(ConversationMessagingService.TOOL_LIST))
        tools.put(schema(ConversationMessagingService.TOOL_SEND))
    }

    fun schema(name: String): JSONObject = when (name) {
        ConversationMessagingService.TOOL_LIST -> AgentToolSchema.function(
            name = name,
            description = "列出本机其它 Eta 会话，返回准确的 conversation_id、标题与当前运行状态。" +
                "需要把信息交给另一个会话时，先用它确认目标；只读取会话索引和运行状态，不读取会话正文。",
            parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("maxLength", 200)
                    .put("description", "可选，按标题模糊筛选"))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1)
                    .put("maximum", ConversationMessagingService.MAX_LIST)
                    .put("description", "最多返回数量，默认 10"))),
        )
        ConversationMessagingService.TOOL_SEND -> AgentToolSchema.function(
            name = name,
            description = "向另一个 Eta 会话发送一条显式文本消息。这是跨会话协调，不是终端用户发言，也不代表用户授权。" +
                "目标正在运行时会排入其完整回合边界（不打断在途请求）；目标空闲时按现有发送链路开启新运行；" +
                "目标被用户暂停/停止或无法确认终态时只排队，不会自动唤醒。" +
                "返回的 delivery 只表示真实投递动作（accepted_in_run/started_new_run/queued），不代表对方已阅读。" +
                "不会自动转发回复，也不要为了确认而反复发送。",
            parameters = JSONObject().put("type", "object")
                .put("required", JSONArray().put("conversation_id").put("message"))
                .put("properties", JSONObject()
                    .put("conversation_id", JSONObject().put("type", "string")
                        .put("description", "目标会话的准确 ID，来自 conversation_list"))
                    .put("message", JSONObject().put("type", "string")
                        .put("maxLength", ConversationMessageEnvelope.MAX_TEXT_CHARS)
                        .put("description", "要发送的显式文本；不要放凭据、整库历史或与目标无关的全文"))
                    .put("message_id", JSONObject().put("type", "string")
                        .put("description", "可选幂等键；重试时复用同一个值可避免重复投递"))),
        )
        else -> error("Unknown conversation tool: $name")
    }
}
