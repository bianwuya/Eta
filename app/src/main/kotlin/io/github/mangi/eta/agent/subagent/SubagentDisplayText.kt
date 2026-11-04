package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentTraceFormatter
import org.json.JSONArray
import org.json.JSONObject

/** Conservative display projection, separate from the real request/result used by the model. */
internal object SubagentDisplayText {
    private val sensitiveKey = Regex("(?i).*(?:password|passwd|credential|authorization|cookie|secret|token|api[_-]?key|signature).*")
    fun redact(text: String): String {
        val parsed = runCatching {
            when {
                text.trimStart().startsWith("{") -> project(JSONObject(text)).toString()
                text.trimStart().startsWith("[") -> project(JSONArray(text)).toString()
                else -> text
            }
        }.getOrDefault(text)
        return AgentTraceFormatter().redactForDisplay(parsed)
            .replace(Regex("(?i)(?:Bearer\\s+)[A-Za-z0-9._~+/=-]+"), "Bearer <已隐藏>")
    }
    private fun project(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { result -> value.keys().forEach { key ->
            result.put(key, if (sensitiveKey.matches(key)) "<已隐藏>" else project(value.opt(key)))
        } }
        is JSONArray -> JSONArray().also { result -> (0 until value.length()).forEach { result.put(project(value.opt(it))) } }
        else -> value
    }
}
