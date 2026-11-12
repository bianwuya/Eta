package io.github.mangi.eta.local.subagent

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.officialpreview.ProductionUiHarness
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.local.subagentstats.*
import io.github.mangi.eta.local.imagegen.ImageGenGroup
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
class SubagentPriorityUiTest(private val style: AppearanceUiStyle) {
    companion object {
        @JvmStatic @Parameters(name = "{0}") fun themes() = AppearanceUiStyle.entries.map { arrayOf(it) }
    }
    private val models = listOf(Model("high", "model-high", "model-high"), Model("low", "model-low", "model-low"))

    @Test fun actualPointerDragPersistsAndReloadsPriorityWithoutReplacingOtherSettings() {
        val original = runBlocking { SettingsDataStore.subagentSettings() }
        val settings = original.copy(modelId = "fixed-model", reasoningEffort = "high", collectRequestStats = true,
            modelPriority = listOf("high", "low"))
        runBlocking { SettingsDataStore.setSubagentSettings(settings) }
        try {
            ProductionUiHarness(style) {
                val scope = rememberCoroutineScope()
                val current by SettingsDataStore.subagentSettingsFlow().collectAsState(initial = settings)
                val rows: @Composable () -> Unit = {
                    ImageGenGroup("模型优先级") { SubagentPriorityRows(models, current.modelPriority) { ids -> scope.launch { RuntimeConfigRepository.setSubagentModelPriority(ids) } } }
                }
                if (style == AppearanceUiStyle.COUI) CouiScaffoldPage("子代理设置", {}) { rows() }
                else MiuixScaffoldPage("子代理设置", {}) { item { rows() } }
            }.use { ui ->
                ui.capture("${style.name.lowercase()}-priority-before")
                ui.drag("拖动调整 model-high 的优先级", 145f)
                ui.await("drag must be persisted") { runBlocking { SettingsDataStore.subagentSettings().modelPriority == listOf("low", "high") } }
                assertEquals(settings.copy(modelPriority = listOf("low", "high")), runBlocking { SettingsDataStore.subagentSettings() })
                assertTrue(ui.texts().contains("1  model-low"))
                ui.capture("${style.name.lowercase()}-priority-after")
            }
            ProductionUiHarness(style) { SubagentPriorityRows(models, runBlocking { SettingsDataStore.subagentSettings().modelPriority }) {} }.use {
                assertTrue("recreated screen must use persisted order", it.texts().contains("1  model-low"))
            }
        } finally { runBlocking { SettingsDataStore.setSubagentSettings(original) } }
    }

    @Test fun cancelledDragDoesNotSaveAndAccessibilityActionCanReorder() {
        val saved = mutableListOf<List<String>>()
        ProductionUiHarness(style) { Column { SubagentPriorityRows(models, emptyList(), onSave = { saved += it }) } }.use { ui ->
            ui.drag("拖动调整 model-high 的优先级", 145f, cancel = true)
            assertTrue(saved.isEmpty())
            assertTrue(ui.texts().contains("1  model-high"))
            val handle = ui.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("拖动调整 model-high 的优先级") == true }
            assertTrue(handle.config[SemanticsActions.CustomActions].first { it.label == "下移" }.action())
            ui.advance()
            assertEquals(listOf(listOf("low", "high")), saved)
        }
    }

    @Test fun summaryUsesSameRecordsAndKeepsMissingTokensUnknown() {
        val record = SubagentRequestStat(conversationId = "fixture", subagentId = "child", invocationId = "call", parentRunId = "run",
            profile = "default", providerId = "provider", model = "model-low", purpose = "chat", ordinal = 1,
            inputTokens = 100, cachedTokens = null, outputTokens = 5)
        val text = subagentSettingsSummary(listOf(record))
        assertTrue(text.contains("输入 100")); assertTrue(text.contains("输出 5"))
        assertTrue(text.contains("缓存 未知")); assertTrue(text.contains("命中率 未知"))
        assertTrue(text.contains("model-low"))
    }
}
