package io.github.mangi.eta.local.longshot

import android.app.Application
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.CancellationSignal
import android.view.ScrollCaptureTarget
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
            assertFalse(host.canScrollVertically(1))
            assertTrue(host.viewport.canScrollVertically(1))
            assertFalse(host.viewport.canScrollVertically(-1))
            val info = AccessibilityNodeInfo.obtain()
            host.viewport.onInitializeAccessibilityNodeInfo(info)
            assertTrue(host.viewport.childCount > 0)
            val first = host.viewport.getChildAt(0)
            val firstTop = first.top
            activity.listState.requestScrollToItem(0, 20)
            repeat(3) {
                ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 320, 640)
            }
            host.viewport.onInitializeAccessibilityNodeInfo(info)
            assertSame(first, host.viewport.getChildAt(0))
            assertEquals(firstTop - 20, first.top)
            ColorOsLongshotHost.install(activity)
            assertSame(host, content.getChildAt(0))
            assertEquals(2, host.childCount)
            controller.pause()
            assertTrue("Screenshot overlay pause must not remove the visible list", host.viewport.canScrollVertically(1))
            controller.resume()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeNodeAndRootDispatchFindTheActualComposeCaptureCallback() {
        ShadowBuild.setManufacturer("OnePlus")
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup().visible()
        try {
            val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
            repeat(4) {
                ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 320, 640)
            }
            val host = content.getChildAt(0) as ColorOsLongshotHost
            val direct = mutableListOf<ScrollCaptureTarget>()
            host.onScrollCaptureSearch(Rect(0, 0, 320, 640), Point(), direct::add)
            assertEquals("Native discovery must forward to Compose, not a FrameLayout renderer", 1, direct.size)
            assertNotSame(host, direct.single().containingView)
            var bounds: Rect? = null
            direct.single().callback.onScrollCaptureSearch(CancellationSignal()) { bounds = it }
            assertNotNull(bounds)
            assertTrue(bounds!!.height() > 0)
            val native = mutableListOf<ScrollCaptureTarget>()
            host.viewport.onScrollCaptureSearch(Rect(0, 0, host.viewport.width, host.viewport.height),
                Point(host.viewport.left, host.viewport.top), native::add)
            assertEquals(1, native.size)
            assertSame(direct.single().containingView, native.single().containingView)
            val dispatched = mutableListOf<ScrollCaptureTarget>()
            host.dispatchScrollCaptureSearch(Rect(0, 0, 320, 640), Point(), dispatched::add)
            assertEquals("Do not publish a duplicate capture target", 1, dispatched.size)
        } finally { controller.pause().stop().destroy() }
    }

    class ListActivity : ComponentActivity() {
        lateinit var listState: LazyListState
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                val state = rememberLazyListState()
                listState = state
                LazyColumn(state = state, modifier = Modifier.colorOsLongshotTarget(state, topInset = 16.dp, bottomInset = 24.dp)) {
                    items(50) { Spacer(Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
            ColorOsLongshotHost.install(this)
        }
    }

    @Test fun exposesActualListMetricsAndOnlyTargetScrolls() {
        val host = ColorOsLongshotHost(RuntimeEnvironment.getApplication())
        var consumed = 0f
        var rows = listOf(ColorOsLongshotHost.Item("a", -20, 160), ColorOsLongshotHost.Item("b", 140, 200))
        val target = object : ColorOsLongshotHost.Target {
            override fun canScroll(direction: Int) = direction != 0
            override val offset = 120
            override val extent = 800
            override val range = 3200
            override val bounds = Rect(0, 100, 320, 900)
            override val items get() = rows
            override fun scrollBy(delta: Float) { consumed += delta }
        }
        assertFalse(host.canScrollVertically(1))
        host.bind(target)
        assertFalse(host.canScrollVertically(1))
        val viewport = host.viewport
        assertTrue(viewport.canScrollVertically(1))
        assertTrue(viewport.canScrollVertically(-1))
        val node = AccessibilityNodeInfo.obtain()
        viewport.onInitializeAccessibilityNodeInfo(node)
        assertTrue(node.isScrollable)
        assertEquals(100, viewport.top)
        assertEquals(800, viewport.height)
        assertEquals(2, viewport.childCount)
        val firstRow = viewport.getChildAt(0)
        rows = listOf(ColorOsLongshotHost.Item("a", -60, 160), ColorOsLongshotHost.Item("b", 100, 200))
        viewport.onInitializeAccessibilityNodeInfo(node)
        assertSame(firstRow, viewport.getChildAt(0))
        assertEquals(-60, firstRow.top)
        viewport.scrollBy(0, 50)
        assertEquals(50f, consumed)
        viewport.scrollTo(0, 220)
        assertEquals(150f, consumed)
        assertEquals(0, host.scrollY)
        host.unbind(target)
        assertFalse(viewport.canScrollVertically(1))
        assertEquals(0, viewport.childCount)
        // Modern capture excludes the wrapper only, not its descendants.
        assertEquals(View.SCROLL_CAPTURE_HINT_EXCLUDE, host.scrollCaptureHint)
    }
}
