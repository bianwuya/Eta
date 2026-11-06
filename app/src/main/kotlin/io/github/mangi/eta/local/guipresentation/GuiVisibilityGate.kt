package io.github.mangi.eta.local.guipresentation

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One bounded wait per action, not a service poller. Queued expired requests cannot present. */
internal class GuiVisibilityGate(
    private val isMainThread: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val timeoutMs: Long = 2_500L,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val pending = ConcurrentHashMap.newKeySet<Attempt>()
    @Volatile private var closed = false

    internal class Attempt(
        private val isCurrent: () -> Boolean,
        private val deadlineNanos: Long,
        private val nanoTime: () -> Long,
    ) {
        private val ready = CountDownLatch(1)
        @Volatile private var result: ToolExecutionDecision? = null
        val allowed: Boolean get() = result == ToolExecutionDecision.Allow

        fun canPresent(): Boolean = result == null && nanoTime() < deadlineNanos && isCurrent()

        @Synchronized
        fun complete(decision: ToolExecutionDecision): Boolean {
            if (result != null) return false
            result = when {
                nanoTime() >= deadlineNanos -> timeout()
                !isCurrent() -> stale()
                else -> decision
            }
            ready.countDown()
            return true
        }

        @Synchronized
        fun reject(decision: ToolExecutionDecision.Reject) {
            if (result != null) return
            result = decision
            ready.countDown()
        }

        fun await(): ToolExecutionDecision {
            val remaining = (deadlineNanos - nanoTime()).coerceAtLeast(0L)
            if (!ready.await(remaining, TimeUnit.NANOSECONDS)) reject(timeout())
            return result ?: timeout()
        }
    }

    fun await(
        controller: AgentRunController,
        isCurrent: () -> Boolean,
        present: (Attempt) -> Unit,
        disposed: (Attempt) -> Unit = {},
    ): ToolExecutionDecision {
        if (isMainThread()) return ToolExecutionDecision.Reject(
            "GUI_VISIBILITY_MAIN_THREAD", "前台操作不能在主线程等待提示，本次未执行",
        )
        if (controller.isCancelled) throw AgentRunCancelledException()
        if (closed || !isCurrent()) return stale()
        val attempt = Attempt(isCurrent, nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs), nanoTime)
        pending.add(attempt)
        val cancellation = controller.register { attempt.reject(stale()) }
        try {
            if (closed) attempt.reject(stale())
            dispatch {
                if (attempt.canPresent()) {
                    try { present(attempt) } catch (_: Exception) {
                        attempt.reject(ToolExecutionDecision.Reject(
                            "GUI_PRESENTATION_FAILED", "操作提示窗口建立失败，本次未执行",
                        ))
                    }
                } else {
                    attempt.reject(stale())
                }
            }
            val result = attempt.await()
            // A user pause must let withAction drain and publish its Resume control.
            if (controller.isCancelled) throw AgentRunCancelledException()
            return if (result == ToolExecutionDecision.Allow && (closed || !isCurrent())) stale() else result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            attempt.reject(stale())
            cancellation.close()
            pending.remove(attempt)
            dispatch { disposed(attempt) }
        }
    }

    override fun close() {
        closed = true
        pending.forEach { it.reject(stale()) }
    }

    companion object {
        private fun stale() = ToolExecutionDecision.Reject(
            "GUI_PRESENTATION_STALE", "手机操作权已变化或任务已结束，本次未执行；请重新观察",
        )
        private fun timeout() = ToolExecutionDecision.Reject(
            "GUI_VISIBILITY_TIMEOUT", "未能及时确认操作提示已附着并可见，本次未执行",
        )
    }
}
