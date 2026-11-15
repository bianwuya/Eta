package io.github.mangi.eta.local.compaction

import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.CompactionSettings
import io.github.mangi.eta.officialpreview.ProductionUiHarness
import kotlinx.coroutines.runBlocking
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
class CompactionSettingsPersistenceTest(private val style: AppearanceUiStyle) {
    companion object {
        @JvmStatic @Parameters(name = "{0}") fun styles() = AppearanceUiStyle.entries.map { arrayOf(it) }
    }

    @Test fun savedPromptAndModelSurviveReopeningTheActualPage() {
        val before = runBlocking { SettingsDataStore.compactionSettings() }
        val initial = CompactionSettings(modelId = null, reasoningEffort = "high", prompt = "original fixture prompt")
        runBlocking { SettingsDataStore.setCompactionSettings(initial) }
        try {
            ProductionUiHarness(style) { CompactionSettingsScreen {} }.use { ui ->
                ui.scrollTo("压缩提示词")
                ui.edit("压缩提示词", "keep all user constraints and current child model")
                ui.scrollTo("保存提示词"); ui.click("保存提示词")
                ui.await("compaction prompt persisted") {
                    runBlocking { SettingsDataStore.compactionSettings().prompt } == "keep all user constraints and current child model"
                }
                ui.capture("${style.name.lowercase()}-compaction-saved")
            }
            ProductionUiHarness(style) { CompactionSettingsScreen {} }.use { ui ->
                ui.scrollTo("压缩提示词")
                assertTrue(ui.nodes().any {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text ==
                        "keep all user constraints and current child model"
                })
                val restored = runBlocking { SettingsDataStore.compactionSettings() }
                assertNull(restored.modelId)
                assertEquals("high", restored.reasoningEffort)
                ui.capture("${style.name.lowercase()}-compaction-reopened")
            }
        } finally { runBlocking { SettingsDataStore.setCompactionSettings(before) } }
    }
}
