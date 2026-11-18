package io.github.mangi.eta.local.imagegen

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class ImageGenConfigStoreTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun modelSelectionRoundTripsWithoutTouchingChatSelectionOrCredentials() = runBlocking {
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        SettingsDataStore.setSelection("chat-provider", "chat-a")
        val credentials = ImageGenFixtureCredentials()
        val chat = ProviderCredential(ProviderAuthModes.API_KEY, "fixture-chat", accessToken = "fixture-chat-key")
        credentials.write("chat-provider", chat)
        val store = ImageGenConfigStore(files.root.imageGenFixtureContext(), credentials)
        assertNull(store.connection())
        val target = checkNotNull(ImageGenEndpoint.resolve("https://images.example/v1"))
        val initial = store.save(target, "gpt-image-test", "fixture-images-key")
        val selected = store.save(target, "refresh", "")
        assertEquals(initial.credentialId, selected.credentialId)
        assertEquals("refresh", store.read()?.model)
        assertEquals("fixture-images-key", store.connection()?.apiKey)
        assertFalse(File(files.root, "imagegen/connection-v1.json").readText().contains("fixture-images-key"))
        assertEquals(chat, credentials.read("chat-provider"))
        assertEquals("chat-a", SettingsDataStore.selectedModelIdFlow().first())
        SettingsDataStore.setSelection("chat-provider", "chat-b")
        assertEquals("refresh", store.read()?.model)

        store.remove()
        assertNull(store.connection())
        assertEquals(setOf("chat-provider"), credentials.slots.keys)
    }

    @Test
    fun editedEndpointAndMissingKeyFailClosed() {
        val credentials = ImageGenFixtureCredentials()
        val store = ImageGenConfigStore(files.root.imageGenFixtureContext(), credentials)
        val before = checkNotNull(ImageGenEndpoint.resolve("https://images.example"))
        val edited = checkNotNull(ImageGenEndpoint.resolve("https://edited.example"))
        store.save(before, "refresh", "fixture-images-key")
        assertNull(store.savedKeyFor(edited))
        assertThrows(IllegalArgumentException::class.java) { store.save(edited, "refresh", "") }
        assertEquals(before, store.connection()?.endpoint)
        assertTrue(credentials.slots.keys.single().startsWith("imagegen:key:"))
        credentials.slots.clear()
        assertNull(store.connection())
        assertNull(store.savedKeyFor(before))
    }
}

internal class ImageGenFixtureCredentials : ProviderCredentialStorage {
    val slots = mutableMapOf<String, ProviderCredential>()
    override fun read(providerId: String): ProviderCredential? = slots[providerId]
    override fun write(providerId: String, credential: ProviderCredential) { slots[providerId] = credential }
    override fun delete(providerId: String) { slots.remove(providerId) }
}

internal fun File.imageGenFixtureContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = this@imageGenFixtureContext
}
