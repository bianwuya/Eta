package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.config.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object ImageGenSource {
    const val NONE = "none"
    const val CODEX_SUBSCRIPTION = "codex_subscription"
    const val OPENAI_API = "openai_api"
}

@Serializable
internal data class ImageGenConfig(
    val source: String = ImageGenSource.NONE,
    val providerId: String = "",
    val accountId: String = "",
    val model: String = "gpt-image-2",
    val size: String = "1024x1024",
    val quality: String = "auto",
    val background: String = "auto",
) {
    fun validate(): String? = when {
        source !in setOf(ImageGenSource.CODEX_SUBSCRIPTION, ImageGenSource.OPENAI_API) -> "请先选择生图来源"
        providerId.isBlank() -> "请选择固定生图服务商"
        source == ImageGenSource.CODEX_SUBSCRIPTION && accountId.isBlank() -> "请选择固定 Codex 账号"
        model.isBlank() || model.length > 120 -> "请填写生图模型"
        source == ImageGenSource.CODEX_SUBSCRIPTION && model != "gpt-image-2" -> "Codex 订阅生图模型为 gpt-image-2"
        size !in SIZES -> "请选择支持的图片尺寸"
        quality !in QUALITIES -> "请选择支持的质量"
        background !in BACKGROUNDS -> "请选择支持的背景"
        else -> null
    }
    companion object {
        val SIZES = listOf("auto", "1024x1024", "1536x1024", "1024x1536")
        val QUALITIES = listOf("auto", "low", "medium", "high")
        val BACKGROUNDS = listOf("auto", "opaque", "transparent")
    }
}

internal object ImageGenConfigStore {
    private const val KEY = "image_gen_config_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Prefs.LOCAL_AGENT_GROUP, Context.MODE_PRIVATE)
    fun read(context: Context): ImageGenConfig = prefs(context).getString(KEY, null)?.let {
        // A damaged configuration is not silently replaced by another paid source.
        json.decodeFromString<ImageGenConfig>(it)
    } ?: ImageGenConfig()
    fun write(context: Context, config: ImageGenConfig) {
        check(prefs(context).edit().putString(KEY, json.encodeToString(config)).commit()) { "生图配置保存失败" }
    }
}
