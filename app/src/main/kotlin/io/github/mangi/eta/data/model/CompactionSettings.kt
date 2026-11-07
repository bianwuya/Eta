package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class CompactionSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val prompt: String = "",
)
