package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.config.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    val profileId: String = "",
    val name: String = "",
    val endpoint: String = "",
    val keyConfigured: Boolean = false,
    val credentialId: String = "",
    val retiredCredentialIds: List<String> = emptyList(),
    val endpointOverride: String = "",
    val protocol: String = "openai_images",
) {
    val credentialPoolId: String get() = if (profileId.isBlank()) providerId else "imagegen:$profileId"
    val keySlotId: String get() = credentialId.ifBlank { profileId }
    fun validate(): String? = when {
        source !in setOf(ImageGenSource.CODEX_SUBSCRIPTION, ImageGenSource.OPENAI_API) -> "请先选择生图来源"
        profileId.isBlank() -> "旧生图配置需要手动创建独立连接；不会继承语言服务商的计费来源"
        source == ImageGenSource.OPENAI_API && protocol != "openai_images" -> "当前仅支持 OpenAI Images 兼容协议"
        source == ImageGenSource.OPENAI_API && runCatching { ImageGenEndpoint.resolve(endpoint, endpointOverride) }.isFailure ->
            "生图地址无效：请填写根 URL、/v1 或完整 endpoint；特殊路径使用高级设置"
        source == ImageGenSource.OPENAI_API && !keyConfigured -> "请填写独立生图 API Key"
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
    private const val LEGACY_KEY = "image_gen_config_v1"
    private const val KEY = "image_gen_profiles_v2"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Prefs.LOCAL_AGENT_GROUP, Context.MODE_PRIVATE)
    fun profiles(context: Context): ImageGenProfiles = prefs(context).getString(KEY, null)?.let {
        json.decodeFromString<ImageGenProfiles>(it)
    } ?: ImageGenProfiles()
    fun legacy(context: Context): ImageGenConfig? = prefs(context).getString(LEGACY_KEY, null)?.let {
        json.decodeFromString<ImageGenConfig>(it)
    }
    fun read(context: Context): ImageGenConfig {
        val state = profiles(context)
        return state.profiles.firstOrNull { it.profileId == state.selectedId } ?: ImageGenConfig()
    }
    @Synchronized
    fun write(context: Context, config: ImageGenConfig) {
        require(config.profileId.isNotBlank())
        val old = profiles(context)
        writeProfiles(context, old.copy(profiles = old.profiles.filterNot { it.profileId == config.profileId } + config,
            selectedId = config.profileId))
    }
    @Synchronized
    fun select(context: Context, profileId: String?) {
        val old = profiles(context)
        require(profileId == null || old.profiles.any { it.profileId == profileId })
        writeProfiles(context, old.copy(selectedId = profileId))
    }
    @Synchronized
    fun remove(context: Context, profileId: String) {
        val old = profiles(context)
        writeProfiles(context, old.copy(profiles = old.profiles.filterNot { it.profileId == profileId },
            selectedId = old.selectedId?.takeIf { it != profileId }))
    }
    @Synchronized
    internal fun writeProfiles(context: Context, state: ImageGenProfiles) {
        require(state.profiles.all { it.profileId.isNotBlank() } && state.profiles.map { it.profileId }.distinct().size == state.profiles.size)
        require(state.selectedId == null || state.profiles.any { it.profileId == state.selectedId })
        check(prefs(context).edit().putString(KEY, json.encodeToString(state)).commit()) { "生图配置保存失败" }
        ImageGenAvailability.reload(context)
    }
}

@Serializable
internal data class ImageGenProfiles(val profiles: List<ImageGenConfig> = emptyList(), val selectedId: String? = null)

/** Field-only cached availability; tool listing does not perform database/network/credential reads. */
internal object ImageGenAvailability {
    @Volatile var available: Boolean = false
        private set
    fun reload(context: Context) {
        available = try { ImageGenConfigStore.read(context).validate() == null } catch (_: Exception) { false }
    }
}
