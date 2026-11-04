package io.github.mangi.eta.ui.components

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SubagentDetailsToolbarTest {
    @Test fun contentActionsFitNarrowScreenWithLargeText() = checkToolbar(280, 1.5f, content = true)

    @Test fun directoryActionsFitWithLargeText() = checkToolbar(320, 2f, content = false)

    private fun checkToolbar(width: Int, fontScale: Float, content: Boolean) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.setContent {
                MiuixTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        SubagentDetailsToolbar(
                            title = "A very long retained tool execution report title",
                            onBack = if (content) ({}) else null,
                            onCopy = if (content) ({}) else null,
                            onRefresh = {},
                            onDelete = if (content) null else ({}),
                        )
                    }
                }
            }
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            repeat(4) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 640)
            }
            fun find(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            val buttons = nodes(requireNotNull(find(root)).semanticsOwner.rootSemanticsNode)
                .filter { it.config.contains(SemanticsActions.OnClick) }
                .map { it.boundsInRoot }.sortedBy { it.left }
            assertEquals(if (content) 3 else 2, buttons.size)
            buttons.forEach { bounds ->
                assertTrue(bounds.left >= 0 && bounds.right <= width + 1)
                assertTrue(bounds.width >= 40 && bounds.height >= 40)
            }
            buttons.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1) }
        } finally { controller.pause().stop().destroy() }
    }
}
