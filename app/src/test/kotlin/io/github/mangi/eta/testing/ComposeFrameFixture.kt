package io.github.mangi.eta.testing

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** The paused Robolectric looper alone need not advance an attached Compose window's frames. */
internal class ComposeFrameFixture(
    private val activity: ComponentActivity,
    private val widthPx: Int,
    private val heightPx: Int,
) : AutoCloseable {
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val looper = shadowOf(Looper.getMainLooper())

    fun setContent(content: @Composable () -> Unit) {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContentView(ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent(content)
        })
    }

    fun advanceFrame() {
        Snapshot.sendApplyNotifications()
        looper.idleFor(16, TimeUnit.MILLISECONDS)
        clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
        activity.window.decorView.let { decor ->
            forceLayoutTree(decor)
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, widthPx, heightPx)
        }
        looper.idle()
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (index in 0 until view.childCount) forceLayoutTree(view.getChildAt(index))
    }

    override fun close() {
        recomposer.cancel()
        scope.cancel()
        looper.idle()
    }
}
