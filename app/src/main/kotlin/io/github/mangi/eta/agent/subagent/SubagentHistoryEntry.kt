package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.util.UUID
import kotlinx.serialization.Serializable

/** A display-only index: bodies are read separately from history-<id> pages. */
@Serializable
internal data class SubagentHistoryEntry(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
)

/** Collects only visible events. Nothing here is fed into the model transcript or resume context. */
internal class SubagentHistoryRecorder(
    private val store: SubagentDetailStore,
    private val detailId: String,
) {
    private data class PendingText(val round: Int, val index: Int, val text: StringBuilder = StringBuilder(), var truncated: Boolean = false,
        val id: String = UUID.randomUUID().toString(), var checkpointLength: Int = -1, var checkpointHidden: String? = null,
        var checkpointed: Boolean = false)
    private data class ToolEntry(val id: String, val title: String, val preview: String, var body: String, var hasResult: Boolean = false, var success: Boolean? = null)

    private var pending: PendingText? = null
    private var currentRound = 0
    private var transcriptSeen = 0
    private var seenTextInRound = false
    private val attemptTextIds = mutableListOf<String>()
    private var hiddenTag: String? = null
    private val tools = mutableMapOf<Pair<Int, String>, ToolEntry>()
    private var finished = false

    @Synchronized
    fun onEvent(event: AgentEvent) {
        if (finished) return
        when (event) {
            is AgentEvent.RoundStarted -> {
                if (event.round != currentRound) {
                    flushText("partial")
                    currentRound = event.round
                    seenTextInRound = false
                    attemptTextIds.clear()
                    hiddenTag = null
                }
            }
            is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                if (pending?.let { it.round != event.round || it.index != event.index } == true) flushText("partial")
                val text = pending ?: PendingText(event.round, event.index).also { pending = it }
                appendBounded(text, event.delta)
            }
            is AgentEvent.AssistantBlockEnd -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                if (pending?.let { it.round != event.round || it.index != event.index } == true) flushText("partial")
                if (event.replacementContent != null) {
                    val text = pending ?: PendingText(event.round, event.index).also { pending = it }
                    text.text.setLength(0)
                    text.truncated = false
                    appendBounded(text, event.replacementContent)
                }
                flushText("success")
            }
            is AgentEvent.ModelRetryScheduled -> {
                flushText("partial")
                store.markHistoryStatus(detailId, attemptTextIds, "partial")
                attemptTextIds.clear()
                append("模型重试", "retry", "failed", "${event.displayMessage}\n原因：${event.reasonCode}")
                seenTextInRound = false
                hiddenTag = null
            }
            is AgentEvent.ToolStarted -> {
                flushText("partial")
                val key = event.round to event.toolCallId
                val title = "工具调用 · ${event.name}"
                // Some tools declare sensitivity only with their result. Do not persist a
                // preview that could reveal their arguments before that decision is known.
                val safePreview = if (AgentSensitiveToolPolicy.isSensitive(event.name)) "[敏感工具参数不保存]"
                    else "[工具参数待结果确认后保留]"
                val body = "$safePreview\n[执行中，结果尚未取得]"
                val entry = ToolEntry(newId(), title, safePreview, body)
                if (tools.size >= SubagentDetailStore.MAX_HISTORY_ENTRIES) tools.remove(tools.keys.first())
                tools[key] = entry
                upsert(entry.id, title, "tool", "running", body)
            }
            is AgentEvent.ToolFinished -> {
                val key = event.round to event.toolCallId
                val entry = tools[key] ?: ToolEntry(newId(), "工具调用 · ${event.name}", "[调用详情未取得]", "").also { tools[key] = it }
                if (!entry.hasResult) {
                    val result = if (AgentSensitiveToolPolicy.isSensitive(event.name)) "[敏感工具结果不保存]" else SubagentDisplayText.redact(event.resultSummary)
                    entry.body = "${entry.preview}\n结果：${result.ifBlank { "[结果正文未取得]" }}"
                }
                upsert(entry.id, entry.title, "tool", if (event.success == false) "failed" else "success", entry.body)
                tools.remove(key)
            }
            is AgentEvent.HostedToolStarted -> {
                flushText("partial")
                val key = event.round to event.toolCallId
                val title = "服务商工具 · ${event.name}"
                val entry = ToolEntry(newId(), title, "", "[服务商工具执行中]")
                if (tools.size >= SubagentDetailStore.MAX_HISTORY_ENTRIES) tools.remove(tools.keys.first())
                tools[key] = entry
                upsert(entry.id, title, "tool", "running", entry.body)
            }
            is AgentEvent.HostedToolFinished -> {
                val key = event.round to event.toolCallId
                val entry = tools[key] ?: ToolEntry(newId(), "服务商工具 · ${event.name}", "", "").also { tools[key] = it }
                entry.body = "[服务商未提供工具结果正文]"
                upsert(entry.id, entry.title, "tool", if (event.success) "success" else "failed", entry.body)
                tools.remove(key)
            }
            // THINKING, AssistantReceived.reasoningContent and all other events are deliberately ignored.
            else -> Unit
        }
    }

    /** Publish a bounded live checkpoint, called only on the executor's existing throttled UI boundary. */
    @Synchronized
    fun checkpoint() {
        val text = pending ?: return
        if (finished || text.checkpointLength == text.text.length && text.checkpointHidden == hiddenTag) return
        val before = hiddenTag
        val raw = text.text.toString()
        val visible = visibleText(raw)
        hiddenTag = before // The next checkpoint reparses the entire pending block.
        text.checkpointLength = text.text.length
        text.checkpointHidden = before
        if (visible.isBlank() && !text.truncated && !text.checkpointed) return
        upsert(text.id, "第 ${text.round} 轮回复", "assistant", "running",
            visible + if (text.truncated) "\n[达到单项保留上限，后续内容未保存]" else "")
        text.checkpointed = true
    }

    /** Fallback for a provider that returned an assistant message without streaming TEXT events.
     * This projection observes the already-published transcript, never edits it, and never reads reasoning. */
    @Synchronized
    fun onTranscript(messages: List<AgentModelClient.ConversationMessage>) {
        if (finished) return
        if (messages.size < transcriptSeen) transcriptSeen = 0
        for (message in messages.drop(transcriptSeen)) {
            if (message.role != "assistant" || message.content.isBlank()) continue
            val hadStreamText = seenTextInRound || pending != null
            flushText("success")
            if (!hadStreamText) {
                seenTextInRound = true
                val visible = visibleText(message.content)
                if (visible.isNotBlank()) append("第 $currentRound 轮回复", "assistant", "success", visible)
            }
        }
        transcriptSeen = messages.size
    }

    /** Called after the actual executor returns; rejected calls instead use ToolFinished's safe summary. */
    @Synchronized
    fun onToolResult(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
        if (finished) return
        val entry = tools[call.round to call.id] ?: return
        val sensitive = result.sensitive || AgentSensitiveToolPolicy.isSensitive(call.name)
        val arguments = if (sensitive) "[敏感工具参数不保存]" else SubagentDisplayText.redact(call.argumentsJson)
        val body = if (sensitive) "[敏感工具结果不保存]" else SubagentDisplayText.redact(result.content)
        entry.body = "参数：$arguments\n结果：$body"
        entry.hasResult = true
        entry.success = io.github.mangi.eta.agent.model.AgentTraceFormatter().isSuccessResult(result)
        // Persist the result at its return boundary even if cancellation prevents ToolFinished.
        upsert(entry.id, entry.title, "tool", if (entry.success == false) "failed" else "success", entry.body)
    }

    @Synchronized
    fun finish(status: String, message: String, finalContent: String = "") {
        if (finished) return
        flushText(if (status == "completed") "success" else "partial")
        if (status != "completed") store.markHistoryStatus(detailId, attemptTextIds, "partial")
        if (status == "completed" && finalContent.isNotBlank() && !seenTextInRound) {
            val visible = visibleText(finalContent)
            if (visible.isNotBlank()) append("最终回复", "assistant", "success", visible)
        }
        tools.values.filter { !it.hasResult }.forEach { entry ->
            upsert(entry.id, entry.title, "tool", if (status == "cancelled") "cancelled" else "failed",
                entry.body.replace("[执行中，结果尚未取得]", "[任务已结束，工具结果未取得]")
                    .replace("[服务商工具执行中]", "[任务已结束，服务商工具结果未取得]"))
        }
        tools.clear()
        val title = when (status) { "completed" -> "任务完成"; "cancelled" -> "任务已停止"; else -> "任务失败" }
        append(title, "terminal", status, message, terminal = true)
        finished = true
    }

    private fun appendBounded(target: PendingText, delta: String) {
        val remaining = (SubagentDetailStore.MAX_FIELD_CHARS - target.text.length).coerceAtLeast(0)
        var count = minOf(delta.length, remaining)
        if (count in 1 until delta.length && delta[count - 1].isHighSurrogate()) count--
        target.text.append(delta.take(count))
        if (delta.length > count) target.truncated = true
    }

    private fun flushText(status: String) {
        val text = pending ?: return
        pending = null
        val raw = text.text.toString()
        if (raw.isNotEmpty()) seenTextInRound = true
        val visible = visibleText(raw)
        if (visible.isBlank() && !text.truncated && !text.checkpointed) return
        if (attemptTextIds.size < SubagentDetailStore.MAX_HISTORY_ENTRIES) attemptTextIds += text.id
        upsert(text.id, "第 ${text.round} 轮回复", "assistant", status,
            visible + if (text.truncated) "\n[达到单项保留上限，后续内容未保存]" else "")
    }

    /** Some compatible gateways put tagged thinking into TEXT. Never store tagged content,
     * including an unclosed tag at a failure boundary or one spanning text blocks. */
    private fun visibleText(raw: String): String {
        val opening = Regex("(?i)<(think|thinking|analysis|reasoning)(?:\\s[^>]*)?>")
        val partialOpening = Regex("(?i)^<(?:think|thinking|analysis|reasoning)(?:\\s[^>]*)?$")
        var pos = 0
        return buildString {
            while (pos < raw.length) {
                val hidden = hiddenTag
                if (hidden != null) {
                    val close = Regex("(?i)</" + Regex.escape(hidden) + "\\s*>").find(raw, pos)
                    if (close == null) break
                    hiddenTag = null
                    pos = close.range.last + 1
                } else {
                    val open = opening.find(raw, pos)
                    if (open == null) {
                        val tail = raw.substring(pos)
                        val partial = tail.lastIndexOf('<').takeIf { index ->
                            if (index < 0) false else {
                                val fragment = tail.substring(index).lowercase()
                                listOf("<think", "<thinking", "<analysis", "<reasoning").any { it.startsWith(fragment) } ||
                                    partialOpening.matches(fragment)
                            }
                        }
                        append(if (partial == null) tail else tail.substring(0, partial))
                        if (partial != null) {
                            val fragment = tail.substring(partial + 1).lowercase()
                            hiddenTag = listOf("thinking", "think", "analysis", "reasoning").firstOrNull { fragment.startsWith(it) } ?: "thinking"
                        }
                        break
                    }
                    append(raw.substring(pos, open.range.first))
                    hiddenTag = open.groupValues[1].lowercase()
                    pos = open.range.last + 1
                }
            }
        }
    }

    private fun append(title: String, kind: String, status: String, body: String, terminal: Boolean = false) =
        upsert(newId(), title, kind, status, body, terminal)

    private fun upsert(id: String, title: String, kind: String, status: String, body: String, terminal: Boolean = false) {
        store.recordHistory(detailId, SubagentHistoryEntry(id, title, kind, status), body, terminal)
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
