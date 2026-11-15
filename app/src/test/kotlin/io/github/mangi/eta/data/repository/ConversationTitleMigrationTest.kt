package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class ConversationTitleMigrationTest {
    @Test fun manualActionUsesOfficialTitleModelWhileAutomaticGenerationIsDisabled() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        val model = Model("title-model", "fixture-model", "Fixture")
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(id = "title-provider", name = "fixture",
            baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", models = listOf(model)))
        SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings(enabled = false, modelId = model.id))
        assertNull(RuntimeConfigRepository.titleGeneratorConfig())
        val manual = requireNotNull(RuntimeConfigRepository.titleGeneratorConfig(manual = true))
        assertEquals(model.modelId, manual.model)
        assertFalse(manual.terminalTools)
        assertFalse(SettingsDataStore.conversationTitleSettings().enabled)
    }
}
