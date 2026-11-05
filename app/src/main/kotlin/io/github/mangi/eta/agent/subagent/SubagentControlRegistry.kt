package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parent run owns its live children. Controls are addressed by subagent_id, never by
 * conversation-global state; a command cannot reach a child from another parent run.
 */
internal class SubagentControlRegistry {
    private data class LiveChild(
        val id: String,
        val description: String,
        val toolCallId: String,
        val controller: AgentRunController,
    )

    private val children = ConcurrentHashMap<String, LiveChild>()

    fun register(id: String, description: String, toolCallId: String, controller: AgentRunController): AutoCloseable {
        val child = LiveChild(id, description, toolCallId, controller)
        check(children.putIfAbsent(id, child) == null) { "Subagent already registered: $id" }
        return AutoCloseable { children.remove(id, child) }
    }

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
            ?: return error("INVALID_ARGUMENTS", "subagent_control 参数必须是 JSON 对象")
        val action = args.optString("action").trim()
        if (action == "list") {
            val items = JSONArray()
            children.values.sortedBy { it.id }.forEach { child ->
                items.put(JSONObject()
                    .put("subagent_id", child.id)
                    .put("description", child.description)
                    .put("tool_call_id", child.toolCallId)
                    .put("status", when {
                        child.controller.isCancelled -> "cancelling"
                        child.controller.isPaused -> "pause_requested"
                        child.controller.hasPendingParentDirective -> "revision_pending"
                        else -> "running"
                    }))
            }
            return AgentModelClient.ToolResult(JSONObject().put("ok", true)
                .put("active", items).put("count", items.length()).toString())
        }
        if (action !in setOf("cancel", "revise", "pause", "resume")) {
            return error("UNKNOWN_ACTION", "支持的操作：list、cancel、revise、pause、resume")
        }
        val id = args.optString("subagent_id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENTS", "请指定正在运行的 subagent_id")
        val child = children[id] ?: return error("SUBAGENT_NOT_RUNNING", "子代理 $id 不在本次主代理运行的活动任务中；可能已经结束")
        if (child.controller.isCancelled) return error("SUBAGENT_ALREADY_CANCELLING", "子代理 $id 已收到停止请求")

        return when (action) {
            "cancel" -> {
                child.controller.cancel()
                success(id, "cancel_requested", "已请求停止该子代理；已执行的外部操作不会自动回滚，最终结果请检查后续报告。")
            }
            "pause" -> {
                child.controller.pause()
                success(id, "pause_requested", "已请求在下一个安全检查点暂停；正在进行的网络请求或工具操作可能仍会完成。")
            }
            "resume" -> {
                child.controller.resume()
                success(id, "resume_requested", "已请求继续该子代理。")
            }
            else -> {
                val instruction = args.optString("instruction").trim()
                if (instruction.isEmpty() || instruction.length > 16_000) {
                    return error("INVALID_ARGUMENTS", "revise 必须提供 1–16000 字符的 instruction")
                }
                // An accepted directive enters the *child's* next model boundary. It never
                // impersonates the end user and cannot grant permissions or restart a stopped run.
                if (!child.controller.steerFromParent(instruction)) {
                    error("SUBAGENT_NOT_ACCEPTING", "子代理已停止或已结束模型回合，未接收这条修正；请核实最终结果后重新派发。")
                } else {
                    child.controller.resume()
                    success(id, "revision_queued", "修正已排入子代理下一轮模型请求；不是即时撤回，正在执行的工具操作可能仍会完成。")
                }
            }
        }
    }

    private fun success(id: String, status: String, message: String) =
        AgentModelClient.ToolResult(JSONObject().put("ok", true).put("subagent_id", id)
            .put("status", status).put("message", message).toString())

    private fun error(code: String, message: String) =
        AgentModelClient.ToolResult(JSONObject().put("ok", false)
            .put("code", code).put("message", message).toString())
}
