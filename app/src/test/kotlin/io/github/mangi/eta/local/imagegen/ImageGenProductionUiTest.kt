package io.github.mangi.eta.local.imagegen

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.officialpreview.ProductionUiHarness
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenProductionUiTest(private val style: AppearanceUiStyle) {
    companion object { @JvmStatic @Parameters(name = "{0}") fun themes() = AppearanceUiStyle.entries.map { arrayOf(it) } }
    private val models = listOf(Model("first", "image-model-a", "image-model-a"), Model("second", "image-model-b", "image-model-b"))
    private val credentials = ImageGenCredentials(object : io.github.mangi.eta.agent.auth.ProviderCredentialStorage {
        private val slots = mutableMapOf<String, io.github.mangi.eta.agent.auth.ProviderCredential>()
        override fun read(providerId: String) = slots[providerId]
        override fun write(providerId: String, credential: io.github.mangi.eta.agent.auth.ProviderCredential) { slots[providerId] = credential }
        override fun delete(providerId: String) { slots.remove(providerId) }
    })

    @Test fun productionPageReadsIndependentCatalogSelectsAndPersistsModelWithoutGeneration() {
        val calls = mutableListOf<Pair<String, String>>()
        ProductionUiHarness(style) {
            ImageGenSettingsScreen(onBack = {}, fetchModels = { url, key -> calls += url to key; Result.success(models) })
        }.use { setup ->
            ImageGenConfigStore.write(setup.activity, ImageGenConfig(source = ImageGenSource.OPENAI_API,
                profileId = "catalog-profile", name = "独立生图", endpoint = "https://relay.example", keyConfigured = true,
                credentialId = "fake-key-slot", model = "image-model-a"))
        }
        credentials.write("fake-key-slot", "fixture-key")
        ProductionUiHarness(style) {
            ImageGenSettingsScreen(onBack = {}, fetchModels = { url, key -> calls += url to key; Result.success(models) }, credentialsFactory = { credentials })
        }.use { ui ->
            assertTrue("opening settings must not contact a catalog", calls.isEmpty())
            ui.capture("${style.name.lowercase()}-image-production")
            ui.scrollTo("生图模型"); ui.click("生图模型")
            ui.capture("${style.name.lowercase()}-after-open-models")
            assertEquals("Catalog click was delivered; visible=${ui.texts()}", 1, calls.size)
            ui.await("catalog model visible") { ui.texts().contains("image-model-b") }
            assertEquals(listOf("https://relay.example" to "fixture-key"), calls)
            ui.capture("${style.name.lowercase()}-models-production")
            ui.click("image-model-b")
            ui.advance(45)
            assertEquals("image-model-a", ImageGenConfigStore.read(ui.activity).model)
            ui.scrollTo("保存连接"); ui.click("保存连接")
            ui.await("selected model must persist") { ImageGenConfigStore.read(ui.activity).model == "image-model-b" }
            assertEquals(1, calls.size)
            val raw = ui.activity.getSharedPreferences(io.github.mangi.eta.config.Prefs.LOCAL_AGENT_GROUP, 0).all.toString()
            assertFalse("Key cannot enter profile preferences", raw.contains("fixture-key"))
        }
    }

    @Test fun directoryFailureRetainsCurrentModelAndOffersManualFallback() {
        var calls = 0
        ProductionUiHarness(style) { }.use { setup ->
            ImageGenConfigStore.write(setup.activity, ImageGenConfig(source = ImageGenSource.OPENAI_API,
                profileId = "catalog-failure", name = "独立生图", endpoint = "https://relay.example", keyConfigured = true, model = "original-model"))
            credentials.write("catalog-failure", "fixture-key")
        }
        ProductionUiHarness(style, dark = true) {
            ImageGenSettingsScreen(onBack = {}, fetchModels = { _, _ -> calls++; Result.failure(IllegalStateException("fixture error")) }, credentialsFactory = { credentials })
        }.use { ui ->
            ui.scrollTo("生图模型"); ui.click("生图模型")
            ui.await("failure shown") { ui.texts().any { it.contains("无法读取生图模型目录") } }
            assertEquals(1, calls)
            assertEquals("original-model", ImageGenConfigStore.read(ui.activity).model)
            ui.scrollTo("手动添加模型"); ui.capture("${style.name.lowercase()}-models-failure")
            ui.click("手动添加模型")
            ui.edit("模型 ID", "custom-image")
            ui.capture("${style.name.lowercase()}-manual-model-dialog")
            ui.click("选择")
            ui.scrollTo("保存连接"); ui.click("保存连接")
            ui.await("manual fallback saved") { ImageGenConfigStore.read(ui.activity).model == "custom-image" }
            assertEquals(1, calls)
        }
    }

    @Test fun productionPickerSupportsSearchThemeSwitchAndLargeText() {
        val currentStyle = mutableStateOf(style)
        ProductionUiHarness(style, dark = true, width = 640, height = 1600, fontScale = 1.3f, appearanceStyle = currentStyle) {
            ImageGenModelPickerScreen(models, "image-model-a", false, null, {}, {}) {}
        }.use { ui ->
            assertTrue(ui.texts().contains("image-model-a"))
            ui.edit("搜索模型", "model-b")
            ui.capture("${style.name.lowercase()}-picker-search")
            assertFalse(ui.texts().toString(), ui.texts().contains("image-model-a")); assertTrue(ui.texts().toString(), ui.texts().contains("image-model-b"))
            currentStyle.value = if (style == AppearanceUiStyle.COUI) AppearanceUiStyle.MIUIX else AppearanceUiStyle.COUI
            ui.advance()
            assertFalse(ui.texts().contains("image-model-a")); assertTrue(ui.texts().contains("image-model-b"))
            ui.capture("${style.name.lowercase()}-picker-narrow-large-theme-switch")
        }
    }
}
