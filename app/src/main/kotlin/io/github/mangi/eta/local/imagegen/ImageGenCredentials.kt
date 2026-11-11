package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.data.model.ProviderAuthModes

/** Reuses device-bound AES-GCM storage; profile JSON contains only a nonsecret presence flag. */
internal class ImageGenCredentials(private val storage: ProviderCredentialStorage) {
    constructor(context: Context) : this(EncryptedProviderCredentialStorage(context))
    fun read(profileId: String): String = storage.read(slot(profileId))?.accessToken.orEmpty()
    fun write(profileId: String, key: String) {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' })
        storage.write(slot(profileId), ProviderCredential(authMode = ProviderAuthModes.API_KEY,
            clientId = "eta-imagegen", accessToken = key))
    }
    fun delete(profileId: String) = storage.delete(slot(profileId))
    private fun slot(profileId: String): String {
        require(profileId.isNotBlank())
        return "imagegen:key:$profileId"
    }
}
