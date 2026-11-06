package io.github.mangi.eta.agent.overlay

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent 手势与状态胶囊的避让协调。
 *
 * 胶囊窗口可触摸，无障碍注入的手势与 Root `input` 事件都会被它截走；
 * 手势落在胶囊范围内时，先让胶囊变为不可触摸并淡出，手势完成后再延迟恢复。
 * 恢复带去抖：连续的点击、滑动只在最后一次之后回来，胶囊不会随每一步上下闪动。
 */
internal object AgentOverlayClearance {
    class Rejected(val code: String, message: String) : RuntimeException(message)

    /** 由持有胶囊窗口的服务实现；除 identity 快照外均在主线程调用。 */
    interface Host {
        /** 胶囊窗口在屏幕上的触摸范围（已含边缘余量）；窗口不存在时返回 null。 */
        fun capsuleTouchBounds(): Rect?

        /** 立即放行触摸并开始淡出动画。 */
        fun yieldCapsule(): Boolean

        /** 恢复可触摸并淡入；需要取消已排队的恢复时由 [cancelRestore] 撤销。 */
        fun scheduleCapsuleRestore(delayMs: Long)

        fun cancelRestore()

        /** Thread-safe identity snapshot; stale gestures cannot yield/restore another owner. */
        fun presentationIdentity(): Any? = this
    }

    @Volatile
    private var host: Host? = null

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    /**
     * 在手势前后包裹避让。[area] 为手势覆盖的屏幕范围（点击为点，滑动为起止包围盒）。
     * 调用方在工具线程同步执行手势，这里会等待淡出过半再放行，让点击反馈出现在干净的画面上。
     */
    fun <T> around(area: Rect, gesture: () -> T): T {
        val current = host ?: return gesture()
        if (Looper.myLooper() == Looper.getMainLooper()) return gesture()
        val identity = current.presentationIdentity()
        val yielded = AtomicBoolean(false)
        val pending = AtomicBoolean(true)
        val failure = AtomicReference<Rejected?>()
        val ready = CountDownLatch(1)
        current.post {
            if (!pending.compareAndSet(true, false) || identity != current.presentationIdentity()) {
                failure.set(Rejected("GUI_PRESENTATION_STALE", "操作提示归属已变化，本次手势未执行"))
                ready.countDown()
                return@post
            }
            val bounds = current.capsuleTouchBounds()
            if (bounds != null && Rect.intersects(bounds, area)) {
                current.cancelRestore()
                if (current.yieldCapsule()) yielded.set(true)
                else failure.set(Rejected("GUI_PRESENTATION_FAILED", "操作提示让路失败，本次手势未执行"))
            }
            ready.countDown()
        }
        return try {
            if (!ready.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS)) {
                pending.set(false)
                throw Rejected("GUI_CLEARANCE_TIMEOUT", "未能及时确认操作提示让路，本次手势未执行")
            }
            failure.get()?.let { throw it }
            // 触摸已放行；稍等淡出，让点击反馈出现在干净的画面上。
            if (yielded.get()) Thread.sleep(FADE_HEAD_START_MS)
            if (identity != current.presentationIdentity()) {
                throw Rejected("GUI_PRESENTATION_STALE", "操作提示归属已变化，本次手势未执行")
            }
            gesture()
        } finally {
            pending.set(false)
            // A previous gesture cannot restore a capsule rebound to a new presentation.
            current.post {
                if (yielded.get() && identity == current.presentationIdentity()) {
                    current.scheduleCapsuleRestore(RESTORE_DELAY_MS)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun Host.post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private const val MAX_WAIT_MS = 300L
    private const val FADE_HEAD_START_MS = 90L
    /** 动作后界面通常还会再被观察一次并紧跟下一步，留足间隔再回来以免来回闪动。 */
    private const val RESTORE_DELAY_MS = 900L
}
