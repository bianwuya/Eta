package io.github.mangi.eta.local.longshot

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/** Native legacy-longshot discovery, backed by the actual active Compose scroll state. */
internal class ColorOsLongshotHost(context: Context) : FrameLayout(context) {
    internal interface Target {
        fun canScroll(direction: Int): Boolean
        val offset: Int
        val extent: Int
        val range: Int
        fun scrollBy(delta: Float)
    }
    private var target: Target? = null
    init {
        // Exclude only this wrapper from modern capture. Descendant Compose callbacks still own pixels/bounds.
        scrollCaptureHint = View.SCROLL_CAPTURE_HINT_EXCLUDE
    }
    fun bind(value: Target) { target = value }
    fun unbind(value: Target) { if (target === value) target = null }
    override fun canScrollVertically(direction: Int): Boolean = target?.canScroll(direction) == true
    override fun computeVerticalScrollOffset(): Int = target?.offset ?: 0
    override fun computeVerticalScrollExtent(): Int = target?.extent ?: height
    override fun computeVerticalScrollRange(): Int = target?.range ?: height
    override fun scrollTo(x: Int, y: Int) { target?.scrollBy((y - computeVerticalScrollOffset()).toFloat()) }
    override fun scrollBy(x: Int, y: Int) { target?.scrollBy(y.toFloat()) }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = canScrollVertically(-1) || canScrollVertically(1)
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
            host.addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
internal fun Modifier.colorOsLongshotTarget(state: LazyListState): Modifier = composed {
    if (!ColorOsLongshotHost.supportedDevice()) return@composed this
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = remember(state, scope, lifecycle) { object : ColorOsLongshotHost.Target {
        private val active get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        override fun canScroll(direction: Int) = active && when {
            direction < 0 -> state.canScrollBackward
            direction > 0 -> state.canScrollForward
            else -> false
        }
        override val offset get() = state.scrollIndicatorState?.scrollOffset?.coerceAtLeast(0) ?: 0
        override val extent get() = state.scrollIndicatorState?.viewportSize?.coerceAtLeast(0) ?: 0
        override val range get() = maxOf(extent, state.scrollIndicatorState?.contentSize ?: extent)
        override fun scrollBy(delta: Float) { if (active) scope.launch { state.scrollBy(delta) } }
    } }
    DisposableEffect(view, target, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            ColorOsLongshotHost.owner(view)?.let { host ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) host.bind(target) else host.unbind(target)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); ColorOsLongshotHost.owner(view)?.unbind(target) }
    }
    this.onGloballyPositioned {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ColorOsLongshotHost.owner(view)?.bind(target)
    }
}
