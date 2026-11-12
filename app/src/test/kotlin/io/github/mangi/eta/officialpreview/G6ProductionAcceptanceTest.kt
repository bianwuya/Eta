package io.github.mangi.eta.officialpreview

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.subagent.*
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.*
import io.github.mangi.eta.local.imagegen.ImageGenModelPickerScreen
import io.github.mangi.eta.local.subagentstats.*
import io.github.mangi.eta.ui.components.SubagentInspectorContent
import io.github.mangi.eta.ui.screens.subagent.SubagentSettingsScreen
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class G6ProductionAcceptanceTest(private val style: AppearanceUiStyle) {
    companion object { @JvmStatic @Parameters(name = "{0}") fun themes() = AppearanceUiStyle.entries.map { arrayOf(it) } }
    private val models = listOf(Model("high", "model-high", "model-high", toolCall = true),
        Model("low", "model-low", "model-low", toolCall = true))
    private fun record(id: String, child: String, input: Int?, cache: Int?, output: Int?) = SubagentRequestStat(
        id = id, conversationId = "fixture-conversation", subagentId = child, invocationId = "fixture-call",
        parentRunId = "fixture-run", profile = "default", providerId = "fixture-provider", model = "model-low",
        purpose = "chat", ordinal = 1, status = "completed", inputTokens = input, cachedTokens = cache, outputTokens = output)

    @Test fun realSettingsSummaryAndDragReachRuntimeCandidateOrder() {
        runBlocking {
            ProviderRepository.addProvider(OpenAiCompatibleProviderSetting("priority-provider", "测试连接", "https://fixture.invalid/v1",
                apiKey = "fixture-key", models = models))
            RuntimeConfigRepository.setSelectedModelId("high")
            SettingsDataStore.setSubagentSettings(SubagentSettings(modelId = "high", collectRequestStats = true, modelPriority = listOf("high", "low")))
        }
        SubagentStatsStore.get(org.robolectric.RuntimeEnvironment.getApplication()).apply {
            clear(); save(record("summary", "child", 128000, 96000, 3200))
        }
        ProductionUiHarness(style) { SubagentSettingsScreen({}, {}, {}) }.use { ui ->
            ui.await("settings summary uses real store") { ui.texts().any { it.contains("75.0%") && it.contains("96000") } }
            ui.capture("${style.name.lowercase()}-settings-production-summary")
            ui.scrollTo("1  model-high")
            ui.drag("拖动调整 model-high 的优先级", 145f)
            ui.await("screen drag saves existing settings") {
                runBlocking { SettingsDataStore.subagentSettings().modelPriority == listOf("low", "high") }
            }
            val selected = runBlocking { requireNotNull(RuntimeConfigRepository.currentRuntimeConfig()) }
            assertEquals(listOf("model-low", "model-high"), runBlocking {
                RuntimeConfigRepository.subagentRuntimeCandidates(selected).map { it.model }
            })
            ui.capture("${style.name.lowercase()}-settings-production-reordered")
        }
    }

    @Test fun realStatsDetailAndInspectorKeepUnknownUsageAndChildIsolation() {
        ProductionUiHarness(style) { }.use { setup ->
            SubagentStatsStore.get(setup.activity).apply {
                clear(); save(record("child-a", "child-a", 32000, null, 800)); save(record("child-b", "child-b", 999, 999, 999))
            }
        }
        ProductionUiHarness(style, dark = true) {
            Box(Modifier.height(620.dp)) {
                SubagentInspectorContent(SubagentActivitySnapshot(subagentId = "child-a", model = "model-low", currentAction = "等待模型响应"),
                    running = true, failed = false, onOpenDetails = {})
            }
        }.use { ui ->
            ui.await("inspector gets only original child's records") {
                ui.texts().any { it.contains("输入 32000") && it.contains("输出 800") && it.contains("缓存 未知") }
            }
            assertFalse(ui.texts().any { it.contains("999") })
            ui.capture("${style.name.lowercase()}-inspector-production")
        }
        ProductionUiHarness(style, dark = true) { SubagentStatsScreen({}, conversationId = "fixture-conversation") }.use { ui ->
            ui.await("stats page loaded") { ui.texts().any { it.contains("32000") } }
            ui.capture("${style.name.lowercase()}-stats-production")
        }
    }

    @Test fun officialNavigationRetainsCornerClippingAndBackRestoresState() {
        lateinit var forward: () -> Unit
        lateinit var back: () -> Unit
        ProductionUiHarness(style) {
            val stack = rememberNavBackStack<AppRoute>(AppRoute.Subagents)
            forward = { stack.add(AppRoute.ImageGenSettings) }
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex); Unit }
            // Labelled display fixture: Robolectric does not expose physical rounded corners.
            val deviceRadius = rememberNavSystemCornerRadius()
            NavDisplay(backStack = stack, onBack = back,
                effects = NavDisplayEffects(cornerClipRadius = if (deviceRadius.value > 0f) deviceRadius else 32.dp)) {
                entry<AppRoute.Subagents> { SubagentSettingsScreen({}, {}, {}) }
                entry<AppRoute.ImageGenSettings> { ImageGenModelPickerScreen(models, "model-high", false, null, back, {}) {} }
            }
        }.use { ui ->
            val prefix = "${style.name.lowercase()}-production-navigation"
            ui.capture("$prefix-before")
            forward(); ui.frame(); ui.capture("$prefix-enter-start")
            ui.advance(7); ui.capture("$prefix-enter-mid")
            ui.advance(40); ui.capture("$prefix-enter-end")
            assertTrue(ui.texts().contains("选择生图模型"))
            back(); ui.frame(); ui.capture("$prefix-back-start")
            ui.advance(7); ui.capture("$prefix-back-mid")
            ui.advance(40); ui.capture("$prefix-back-end")
            assertTrue(ui.texts().contains("Subagent"))
            // Interrupt a transition with back, then navigate forward again.
            forward(); ui.advance(3); back(); ui.advance(45); forward(); ui.advance(45)
            assertTrue(ui.texts().contains("选择生图模型"))
        }
    }
}
