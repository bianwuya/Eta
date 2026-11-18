package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Subagent 模型与思考配置。
 *
 * [modelId] 为空表示跟随主 Agent 当前生效的模型；指定启用的 Model.id 时使用其连接。
 * [modelPriority] 仅在用户调整过顺序后生效，仅覆盖同连接的模型选择，不改写 [modelId]。
 */
@Serializable
internal data class SubagentSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val modelPriority: List<String> = emptyList(),
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)

    fun withModelPriority(orderedIds: List<String>): SubagentSettings {
        val ids = orderedIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return this
        val reordered = ids.toHashSet()
        // Keep the existing relative order of entries outside the current connection.
        return copy(modelPriority = ids + modelPriority.filterNot(reordered::contains))
    }
}

internal fun ProviderSetting.subagentPriorityModels(): List<Model> =
    if (!isEnabled) emptyList()
    else models.filter { it.isEnabled && it.supportsTools && it.id.isNotBlank() && it.modelId.isNotBlank() }
        .sortedBy { it.sortOrder }
