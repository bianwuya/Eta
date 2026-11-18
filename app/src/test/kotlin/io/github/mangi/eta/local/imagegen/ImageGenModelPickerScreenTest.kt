package io.github.mangi.eta.local.imagegen

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.testing.ComposeFrameFixture
import io.github.mangi.eta.ui.app.AgentAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h720dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ImageGenModelPickerScreenTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun refreshCandidateCanBeSelectedAndSavedInCoui() = verifySelection(AppearanceUiStyle.COUI)
    @Test fun refreshCandidateCanBeSelectedAndSavedInMiuix() = verifySelection(AppearanceUiStyle.MIUIX)

    private fun verifySelection(style: AppearanceUiStyle) {
        val store = ImageGenConfigStore(files.root.imageGenFixtureContext(), ImageGenFixtureCredentials())
        val target = checkNotNull(ImageGenEndpoint.resolve("https://images.example/v1"))
        store.save(target, "gpt-image-test", "fixture-images-key")
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = activityController.get()
        val frames = ComposeFrameFixture(activity, widthPx = 640, heightPx = 1440)
        val supplied = listOf(ImageGenModelCandidate("refresh", "Synthetic image model"))
        var fetches = 0
        try {
            frames.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = style), applyInterfaceScale = false) {
                    ImageGenSettingsScreen(onBack = {}, storeOverride = store, fetchModels = { url, key ->
                        assertEquals(target, url)
                        assertEquals("fixture-images-key", key)
                        fetches++
                        if (fetches == 1) error("synthetic catalog failure")
                        supplied
                    })
                }
            }
            val modelRow = activity.getString(R.string.image_gen_model)
            val refreshRow = activity.getString(R.string.image_gen_refresh)
            val saveRow = activity.getString(R.string.image_gen_save)
            val activeText = activity.getString(R.string.image_gen_config_active)
            frames.await("saved connection is loaded") { activity.root()?.findText(activeText) != null }
            assertEquals(0, fetches)
            if (activity.clickable(modelRow) == null) activity.scrollToKey("imagegen:connection:model")
            frames.await("model row is reachable") { activity.clickable(modelRow) != null }
            activity.click(modelRow)
            val failureText = activity.getString(R.string.image_gen_catalog_failed)
            frames.await("catalog failure is visible") { activity.root()?.findText(failureText) != null }
            activity.click(refreshRow)
            frames.await("fixed refresh row and raw model ID are both clickable") {
                activity.clickable(refreshRow) != null && activity.clickable("refresh") != null
            }
            assertEquals(2, fetches)
            activity.click("refresh")
            frames.await("return to connection with the selected draft") {
                activity.clickable(modelRow)?.findText("refresh") != null
            }
            assertEquals("gpt-image-test", store.read()?.model) // Selection is a draft until explicitly saved.
            if (activity.clickable(saveRow) == null) activity.scrollToKey("imagegen:connection:save")
            frames.await("save row is reachable") { activity.clickable(saveRow) != null }
            activity.click(saveRow)
            frames.await("raw model ID persisted") { store.read()?.model == "refresh" }
            assertEquals(2, fetches)
        } finally {
            frames.close()
            activityController.pause().stop().destroy()
        }
    }

    private fun ComposeFrameFixture.await(description: String, condition: () -> Boolean) {
        repeat(200) {
            advanceFrame()
            if (condition()) return
            Thread.sleep(5) // Let the fixture's background disk read/write complete.
        }
        throw AssertionError(description)
    }

    private fun ComponentActivity.scrollToKey(key: String) {
        fun scroll(node: SemanticsNode): Boolean {
            val index = node.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(key) ?: -1
            if (index >= 0 && node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(index) == true) {
                return true
            }
            return node.children.any(::scroll)
        }
        assertTrue("No scrollable item with key $key", root()?.let(::scroll) == true)
    }

    private fun ComponentActivity.click(text: String) {
        val node = clickable(text)
        assertNotNull("No enabled row for $text", node)
        assertTrue(node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }

    private fun ComponentActivity.clickable(text: String): SemanticsNode? {
        fun search(node: SemanticsNode): SemanticsNode? {
            if (node.config.contains(SemanticsActions.OnClick) && !node.config.contains(SemanticsProperties.Disabled) &&
                node.findText(text) != null) return node
            return node.children.firstNotNullOfOrNull(::search)
        }
        return root()?.let(::search)
    }

    private fun SemanticsNode.findText(text: String): SemanticsNode? =
        if (config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) this
        else children.firstNotNullOfOrNull { it.findText(text) }

    private fun ComponentActivity.root(): SemanticsNode? {
        fun find(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return find(window.decorView)?.semanticsOwner?.unmergedRootSemanticsNode
    }
}
