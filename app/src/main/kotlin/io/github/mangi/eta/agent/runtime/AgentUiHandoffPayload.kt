package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentUiHandoffPayload(
    val conversationId: String,
    val promptSupplement: Supplement? = null,
    val supplements: List<Supplement> = emptyList(),
    val requestMode: String = "normal",
    val planLineageId: String = "",
) {
    val lastSupplementIndex: Int
        get() = (listOfNotNull(promptSupplement?.index) + supplements.map { it.index }).maxOrNull() ?: 0

    fun promptMessageId(runId: String): String =
        promptSupplement?.let { "user-$runId-supplement-${it.index}" } ?: "user-$runId"

    data class Supplement(
        val index: Int,
        val text: String,
        val createdAt: Long,
        val queueMessageId: String = "",
        val images: List<String> = emptyList(),
    )

    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("conversationId", conversationId)
            .also { if (requestMode == "plan") it.put("requestMode", "plan") }
            .also { if (requestMode == "plan" && planLineageId.isNotBlank()) it.put("planLineageId", planLineageId) }
            .also { json ->
                promptSupplement?.let { json.put("promptSupplement", it.toJson()) }
            }
            .put(
                "supplements",
                JSONArray().also { array ->
                    supplements.forEach { supplement ->
                        array.put(
                            supplement.toJson()
                        )
                    }
                }
            )
            .toString()

    companion object {
        private const val TYPE = "agent_ui_handoff"
        private const val VERSION = 2

        fun from(raw: String): AgentUiHandoffPayload {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) {
                return AgentUiHandoffPayload(conversationId = trimmed)
            }
            return runCatching {
                val json = JSONObject(trimmed)
                if (json.optString("type") != TYPE) {
                    return@runCatching AgentUiHandoffPayload(conversationId = trimmed)
                }
                val supplementsJson = json.optJSONArray("supplements") ?: JSONArray()
                AgentUiHandoffPayload(
                    conversationId = json.optString("conversationId"),
                    requestMode = json.optString("requestMode", "normal").let { if (it == "plan") it else "normal" },
                    planLineageId = json.optString("planLineageId").take(256),
                    promptSupplement = json.optJSONObject("promptSupplement")
                        ?.toSupplement(defaultIndex = 1),
                    supplements = (0 until supplementsJson.length()).mapNotNull { index ->
                        supplementsJson.optJSONObject(index)?.toSupplement(index + 1)
                    }
                )
            }.getOrDefault(AgentUiHandoffPayload(conversationId = trimmed))
        }

        private fun JSONObject.toSupplement(defaultIndex: Int): Supplement? {
            val text = optString("text").trim()
            val images = optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (text.isBlank() && images.isEmpty()) return null
            return Supplement(
                index = optInt("index", defaultIndex),
                text = text,
                createdAt = optLong("createdAt"),
                queueMessageId = optString("queueMessageId"),
                images = images,
            )
        }
    }

    private fun Supplement.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("createdAt", createdAt)
            .put("queueMessageId", queueMessageId)
            .put("images", JSONArray(images))
}
