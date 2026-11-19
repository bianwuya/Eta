package io.github.mangi.eta.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.officialpreview.ProductionUiHarness
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelPickerSheetContentTest {
    @Test
    fun unavailableSecondaryModelShowsReasonAndCannotBeSelectedWhileAvailableModelStillWorks() {
        val unavailable = AgentModelOptionUi(
            id = "unavailable",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = "openai",
            modelId = "unavailable-model-id",
            displayName = "Unavailable compaction model",
            contextWindow = 128_000,
            available = false,
            unavailableReason = "未配置 API Key",
        )
        val available = unavailable.copy(
            id = "available",
            modelId = "available-model-id",
            displayName = "Available compaction model",
            available = true,
            unavailableReason = null,
        )
        val pickerState = AgentModelPickerUiState(
            providerGroups = listOf(
                AgentModelProviderGroupUi(
                    providerId = "provider",
                    providerName = "Provider",
                    providerSourceType = "openai",
                    models = listOf(unavailable, available),
                ),
            ),
        )
        val selections = mutableListOf<String?>()

        ProductionUiHarness(AppearanceUiStyle.MIUIX) {
            ModelPickerSheetContent(
                pickerState = pickerState,
                selectedModelId = null,
                followOption = ModelPickerFollowOption(
                    title = "跟随主模型",
                    subtitle = "使用当前主模型",
                    modelId = null,
                    sourceType = null,
                ),
                onSelectModel = { selections += it },
            )
        }.use { ui ->
            assertNotNull("Unavailable reason must replace the secondary model ID", ui.find("未配置 API Key"))
            val title = requireNotNull(ui.find("Unavailable compaction model"))
            val row = ui.actionNode(title, SemanticsActions.OnClick)
            assertTrue("Unavailable secondary model must expose Disabled semantics",
                row.config.contains(SemanticsProperties.Disabled))
            ui.tap("Unavailable compaction model")
            ui.advance(80)
            assertTrue("Disabled secondary model must not emit a selection", selections.isEmpty())

            ui.click("Available compaction model")
            ui.await("Available secondary model remains selectable") {
                selections == listOf("available")
            }
        }
    }
}
