package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** 只处理 SSE 分帧；各协议自行解释事件并在终态返回 false，不等待服务端关闭连接。 */
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    onEvent: (event: String, data: String) -> Boolean,
) {
    var event = ""
    val dataLines = mutableListOf<String>()

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        runController.throwIfCancelled()
        return onEvent(name, payload)
    }

    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        while (true) {
            runController.throwIfCancelled()
            val line = reader.readLine()
            if (line == null) {
                try {
                    dispatch()
                } catch (_: org.json.JSONException) {
                    // 仅 EOF 尾帧的解析失败视为截断；完整帧中的坏 JSON 仍原样报错。
                    throw AgentModelFailure.incompleteStream("模型接口 SSE 尾帧不完整，连接已结束。")
                }
                break
            }
            when {
                line.isEmpty() -> if (!dispatch()) break
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}
