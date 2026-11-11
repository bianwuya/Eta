package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageGenCredentialsTest {
    @Test fun independentKeysUseCredentialStorageAndNeverEnterProfileJson() {
        val slots = mutableMapOf<String, ProviderCredential>()
        val keys = ImageGenCredentials(object : ProviderCredentialStorage {
            override fun read(providerId: String) = slots[providerId]
            override fun write(providerId: String, credential: ProviderCredential) { slots[providerId] = credential }
            override fun delete(providerId: String) { slots.remove(providerId) }
        })
        val context = RuntimeEnvironment.getApplication()
        keys.write("first", "fixture-secret-first")
        keys.write("second", "fixture-secret-second")
        ImageGenConfigStore.write(context, ImageGenConfig(source = ImageGenSource.OPENAI_API, profileId = "first",
            endpoint = "https://relay.example.invalid/v1/images/generations", keyConfigured = true))
        val prefs = context.getSharedPreferences(io.github.mangi.eta.config.Prefs.LOCAL_AGENT_GROUP, Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString().contains("fixture-secret") })
        assertEquals("fixture-secret-first", keys.read("first"))
        keys.delete("first")
        assertEquals("", keys.read("first"))
        assertEquals("fixture-secret-second", keys.read("second"))
    }
}
