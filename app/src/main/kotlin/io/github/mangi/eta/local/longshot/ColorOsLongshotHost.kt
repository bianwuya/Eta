package io.github.mangi.eta.local.longshot

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ScrollCaptureTarget
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Native legacy-longshot discovery, backed by the actual active Compose scroll state. */
internal class ColorOsLongshotHost(context: Context) : FrameLayout(context) {
    internal data class Item(val key: Any, val top: Int, val height: Int)
    internal interface Target {
        fun canScroll(direction: Int): Boolean
        val offset: Int
        val extent: Int
        val range: Int
        val bounds: Rect
        val items: List<Item>
        fun scrollBy(delta: Float)
    }
    private var target: Target? = null
    internal val viewport = LegacyViewport(context)
    init {
        scrollCaptureHint = View.SCROLL_CAPTURE_HINT_EXCLUDE
        addView(viewport, LayoutParams(0, 0))
    }
    fun bind(value: Target) { target = value; update(value) }
    fun unbind(value: Target) {
        if (target === value) {
            target = null
            viewport.sync(emptyList())
            viewport.layout(0, 0, 0, 0)
        }
    }
    fun update(value: Target) {
        if (target !== value) return
        val bounds = value.bounds
        viewport.measure(MeasureSpec.makeMeasureSpec(bounds.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height().coerceAtLeast(0), MeasureSpec.EXACTLY))
        viewport.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        target?.let(::update)
    }

    /** Geometry only: OEM's legacy stitcher tracks real LazyColumn item identities and movement. */
    internal inner class LegacyViewport(context: Context) : ViewGroup(context) {
        private val rows = linkedMapOf<Any, View>()
        init {
            setWillNotDraw(true)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            scrollCaptureHint = SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS
        }
        override fun canScrollVertically(direction: Int) = target?.canScroll(direction) == true
        override fun computeVerticalScrollOffset() = target?.offset ?: 0
        override fun computeVerticalScrollExtent() = height
        override fun computeVerticalScrollRange() = target?.range ?: height
        override fun scrollTo(x: Int, y: Int) { target?.scrollBy((y - computeVerticalScrollOffset()).toFloat()) }
        override fun scrollBy(x: Int, y: Int) { target?.scrollBy(y.toFloat()) }
        override fun dispatchTouchEvent(event: MotionEvent) = false
        override fun dispatchScrollCaptureSearch(localVisibleRect: Rect, windowOffset: Point, targets: Consumer<ScrollCaptureTarget>) = Unit
        override fun onScrollCaptureSearch(localVisibleRect: Rect, windowOffset: Point, targets: Consumer<ScrollCaptureTarget>) {
            val hostRect = Rect(localVisibleRect).apply { offset(left, top) }
            this@ColorOsLongshotHost.onScrollCaptureSearch(hostRect,
                Point(windowOffset.x - left, windowOffset.y - top), targets)
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // Only materialize geometry when native longshot discovery asks for this node.
            sync(target?.items.orEmpty())
            info.isScrollable = canScrollVertically(-1) || canScrollVertically(1)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        fun sync(items: List<Item>) {
            val visibleKeys = items.mapTo(HashSet()) { it.key }
            val iterator = rows.iterator()
            while (iterator.hasNext()) {
                val row = iterator.next()
                if (row.key !in visibleKeys) { removeView(row.value); iterator.remove() }
            }
            items.forEach { item ->
                val row = rows.getOrPut(item.key) { View(context).also { addView(it) } }
                row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(item.height, MeasureSpec.EXACTLY))
                row.layout(0, item.top, width, item.top + item.height)
                bringChildToFront(row)
            }
        }
    }
    override fun dispatchScrollCaptureSearch(
        localVisibleRect: Rect,
        windowOffset: Point,
        targets: Consumer<ScrollCaptureTarget>,
    ) {
        onScrollCaptureSearch(localVisibleRect, windowOffset, targets)
    }

    override fun onScrollCaptureSearch(
        localVisibleRect: Rect,
        windowOffset: Point,
        targets: Consumer<ScrollCaptureTarget>,
    ) {
        // The native wrapper owns legacy discovery, never the capture pixels or viewport.
        // Also support OEMs that query the selected native node directly instead of dispatching.
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child === viewport) continue
            if (child.visibility != View.VISIBLE) continue
            val dx = child.left - scrollX
            val dy = child.top - scrollY
            val visible = Rect(localVisibleRect).apply { offset(-dx, -dy) }
            if (visible.intersect(0, 0, child.width, child.height)) {
                child.dispatchScrollCaptureSearch(visible, Point(windowOffset.x + dx, windowOffset.y + dy), targets)
            }
        }
    }
    companion object {
        fun install(activity: Activity) {
            if (!supportedDevice()) return
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content.childCount != 1 || content.getChildAt(0) is ColorOsLongshotHost) return
            val compose = content.getChildAt(0)
            val params = compose.layoutParams
            content.removeView(compose)
            val host = ColorOsLongshotHost(activity)
            host.addView(compose, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            content.addView(host, params)
        }
        internal fun supportedDevice(): Boolean =
            Build.MANUFACTURER.equals("OnePlus", true) || Build.MANUFACTURER.equals("OPPO", true) ||
                Build.MANUFACTURER.equals("realme", true)
        private fun find(view: View): ColorOsLongshotHost? {
            var current: View? = view
            while (current != null) {
                if (current is ColorOsLongshotHost) return current
                current = current.parent as? View
            }
            return null
        }
        internal fun owner(view: View) = find(view)
    }
}

/** No polling or injected gestures. Only the visible page exposes its own real list. */
internal fun Modifier.colorOsLongshotTarget(
    state: LazyListState,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
): Modifier = composed {
    if (!ColorOsLongshotHost.supportedDevice()) return@composed this
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val topPx = with(LocalDensity.current) { topInset.roundToPx() }
    val bottomPx = with(LocalDensity.current) { bottomInset.roundToPx() }
    val bounds = remember(state) { Rect() }
    val target = remember(state, scope, lifecycle, topPx, bottomPx) { object : ColorOsLongshotHost.Target {
        // A screenshot overlay can pause the Activity while its content remains visible.
        private val active get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        override fun canScroll(direction: Int) = active && when {
            direction < 0 -> state.canScrollBackward
            direction > 0 -> state.canScrollForward
            else -> false
        }
        override val offset get() = state.scrollIndicatorState?.scrollOffset?.coerceAtLeast(0) ?: 0
        override val extent get() = state.scrollIndicatorState?.viewportSize?.coerceAtLeast(0) ?: 0
        override val range get() = maxOf(extent, state.scrollIndicatorState?.contentSize ?: extent)
        override val bounds get() = bounds
        override val items get(): List<ColorOsLongshotHost.Item> {
            val layout = state.layoutInfo
            return layout.visibleItemsInfo.mapNotNull { item ->
                val top = item.offset - layout.viewportStartOffset - topPx
                if (top + item.size <= 0 || top >= bounds.height()) null
                else ColorOsLongshotHost.Item(item.key, top, item.size)
            }
        }
        override fun scrollBy(delta: Float) { if (active) scope.launch { state.scrollBy(delta) } }
    } }
    DisposableEffect(view, target, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            ColorOsLongshotHost.owner(view)?.let { host ->
                when {
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> host.bind(target)
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> host.unbind(target)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); ColorOsLongshotHost.owner(view)?.unbind(target) }
    }
    this.onGloballyPositioned { coordinates ->
        val host = ColorOsLongshotHost.owner(view) ?: return@onGloballyPositioned
        val window = coordinates.boundsInWindow()
        val location = IntArray(2)
        host.getLocationInWindow(location)
        bounds.set(window.left.roundToInt() - location[0], window.top.roundToInt() - location[1] + topPx,
            window.right.roundToInt() - location[0], (window.bottom.roundToInt() - location[1] - bottomPx)
                .coerceAtLeast(window.top.roundToInt() - location[1] + topPx))
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) host.bind(target) else host.update(target)
    }
}
