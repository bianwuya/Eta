package io.github.mangi.eta.local.longshot

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowLooper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ColorOsLongshotHostTest {
    @Test fun installedHostReadsComposeListAndDoesNotDuplicateOnInstall() {
        ShadowBuild.setManufacturer("OnePlus")
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            repeat(3) {
                ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 320, 640)
            }
            val host = content.getChildAt(0) as ColorOsLongshotHost
            assertTrue(host.canScrollVertically(1))
            assertFalse(host.canScrollVertically(-1))
            ColorOsLongshotHost.install(activity)
            assertSame(host, content.getChildAt(0))
            assertEquals(1, host.childCount)
        } finally { controller.pause().stop().destroy() }
    }

    class ListActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                val state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.colorOsLongshotTarget(state)) {
                    items(50) { Spacer(Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
            ColorOsLongshotHost.install(this)
        }
    }

    @Test fun exposesActualListMetricsAndOnlyTargetScrolls() {
        val host = ColorOsLongshotHost(RuntimeEnvironment.getApplication())
        var delta = 0f
        val target = object : ColorOsLongshotHost.Target {
            override fun canScroll(direction: Int) = direction != 0
            override val offset = 120
            override val extent = 800
            override val range = 3200
            override fun scrollBy(value: Float) { delta += value }
        }
        assertFalse(host.canScrollVertically(1))
        host.bind(target)
        assertTrue(host.canScrollVertically(1))
        assertTrue(host.canScrollVertically(-1))
        val node = AccessibilityNodeInfo.obtain()
        host.onInitializeAccessibilityNodeInfo(node)
        assertTrue(node.isScrollable)
        host.scrollBy(0, 50)
        assertEquals(50f, delta)
        host.scrollTo(0, 220)
        assertEquals(150f, delta)
        assertEquals(0, host.scrollY)
        host.unbind(target)
        assertFalse(host.canScrollVertically(1))
        // Modern capture excludes the wrapper only, not its descendants.
        assertEquals(View.SCROLL_CAPTURE_HINT_EXCLUDE, host.scrollCaptureHint)
    }
}
