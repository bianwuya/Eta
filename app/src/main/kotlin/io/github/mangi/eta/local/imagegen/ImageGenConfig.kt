package io.github.mangi.eta.local.imagegen

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

internal const val IMAGE_GEN_MODEL_ID_MAX = 120

internal fun isImageGenModelId(id: String): Boolean =
    id.isNotBlank() && id.length <= IMAGE_GEN_MODEL_ID_MAX && id.none(Char::isISOControl)

internal fun isImageGenApiKey(key: String): Boolean =
    key.length in 1..4096 && key.all { it in '!'..'~' }

@Serializable
internal data class ImageGenConfig(val endpoint: String, val model: String, val credentialId: String)

internal class ImageGenConnection(val endpoint: HttpUrl, val model: String, val apiKey: String)

/** A locally saved Images connection only: no defaults, provider lookup, chat credentials or legacy login import. */
internal class ImageGenConfigStore(
    context: Context,
    private val credentials: ProviderCredentialStorage = EncryptedProviderCredentialStorage(context),
) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "imagegen/connection-v1.json"))

    fun read(): ImageGenConfig? = synchronized(lock) { readLocked() }

    fun connection(): ImageGenConnection? = synchronized(lock) {
        val config = readLocked() ?: return@synchronized null
        val endpoint = ImageGenEndpoint.resolve(config.endpoint) ?: return@synchronized null
        if (!isImageGenModelId(config.model)) return@synchronized null
        val key = keyFor(config) ?: return@synchronized null
        ImageGenConnection(endpoint, config.model, key)
    }

    /** Never send a previously saved key to an edited destination, even for a Models GET. */
    fun savedKeyFor(endpoint: HttpUrl): String? = synchronized(lock) {
        val config = readLocked() ?: return@synchronized null
        if (ImageGenEndpoint.resolve(config.endpoint) != endpoint) return@synchronized null
        keyFor(config)
    }

    fun save(endpoint: HttpUrl, model: String, newKey: String): ImageGenConfig = synchronized(lock) {
        require(ImageGenEndpoint.resolve(endpoint.toString()) == endpoint && isImageGenModelId(model))
        val previous = readLocked()
        val slot = if (newKey.isEmpty()) {
            require(previous != null && ImageGenEndpoint.resolve(previous.endpoint) == endpoint && keyFor(previous) != null)
            previous.credentialId
        } else {
            require(isImageGenApiKey(newKey))
            UUID.randomUUID().toString().also { id ->
                credentials.write(credentialSlot(id), ProviderCredential(
                    authMode = ProviderAuthModes.API_KEY,
                    clientId = CLIENT_ID,
                    accessToken = newKey,
                ))
            }
        }
        val config = ImageGenConfig(endpoint.toString(), model, slot)
        try {
            val bytes = Json.encodeToString(config).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
            check(readLocked() == config) { "Image connection was not saved" }
        } catch (failure: Exception) {
            if (newKey.isNotEmpty()) runCatching { credentials.delete(credentialSlot(slot)) }
            throw failure
        }
        previous?.credentialId?.takeIf { it != slot }?.let { id ->
            // A replaced slot cannot be used by the active connection even if cleanup fails.
            runCatching { credentials.delete(credentialSlot(id)) }
        }
        config
    }

    fun remove() = synchronized(lock) {
        val previous = readLocked()
        file.delete()
        check(readLocked() == null) { "Image connection was not removed" }
        previous?.let { credentials.delete(credentialSlot(it.credentialId)) }
    }

    private fun readLocked(): ImageGenConfig? = try {
        file.openRead().bufferedReader(Charsets.UTF_8).use { Json.decodeFromString(it.readText()) }
    } catch (failure: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
        null
    }

    private fun keyFor(config: ImageGenConfig): String? = credentials.read(credentialSlot(config.credentialId))
        ?.takeIf { it.authMode == ProviderAuthModes.API_KEY && it.clientId == CLIENT_ID }
        ?.accessToken?.takeIf(::isImageGenApiKey)

    private companion object {
        val lock = Any()
        const val CLIENT_ID = "eta-imagegen"
        fun credentialSlot(id: String) = "imagegen:key:$id"
    }
}

/** Context only at startup; the run's catalog reads local authorization and never calls the provider. */
internal object ImageGenAvailability {
    @Volatile private var store: ImageGenConfigStore? = null

    fun init(context: Context) {
        store = ImageGenConfigStore(context)
    }

    val available: Boolean
        get() = runCatching { store?.connection() != null }.getOrDefault(false)
}
