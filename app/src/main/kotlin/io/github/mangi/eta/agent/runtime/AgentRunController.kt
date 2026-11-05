package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()
    private val children = mutableSetOf<AgentRunController>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    enum class SteeringOrigin { USER, PARENT_AGENT }
    data class SteeringInput(
        val text: String,
        val images: List<io.github.mangi.eta.agent.model.AgentModelClient.ModelImage> = emptyList(),
        val origin: SteeringOrigin = SteeringOrigin.USER,
        val sequence: Long = 0L,
    )

    /**
     * 来自另一个会话的显式消息。与 steering 共用完整 turn 边界，但以运行时数据注入，
     * 不伪装成终端用户发言；[envelopeText] 已包含来源与“非用户授权”说明。
     */
    data class ConversationInput(
        val messageId: String,
        val sourceConversationId: String,
        val sourceTitle: String,
        val sourceRunId: String,
        val createdAt: Long,
        val envelopeText: String,
    )

    private val steeringMessages = ArrayDeque<SteeringInput>()
    private val conversationMessages = ArrayDeque<ConversationInput>()
    @Volatile private var pendingSteering = false
    @Volatile private var steeringCounter = 0L
    val steeringVersion: Long get() = steeringCounter
    private var acceptingSteering = true
    private var acceptingConversationMessages = true
    @Volatile
    private var paused = false
    private var pauseCheckpointReached = false

    val isPaused: Boolean
        get() = paused

    /** True only once an executing worker has actually reached a paused checkpoint. */
    val isPausedAtCheckpoint: Boolean
        get() = lock.withLock { paused && pauseCheckpointReached }

    fun cancel() {
        val childSnapshot = lock.withLock {
            cancelled = true
            acceptingSteering = false
            acceptingConversationMessages = false
            steeringMessages.clear()
            pendingSteering = false
            conversationMessages.clear()
            paused = false
            pauseCheckpointReached = false
            pauseCondition.signalAll()
            children.toList()
        }
        childSnapshot.forEach { it.cancel() }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String, images: List<io.github.mangi.eta.agent.model.AgentModelClient.ModelImage> = emptyList()): Boolean =
        enqueueSteering(text, images, SteeringOrigin.USER)

    /** Parent-agent directives are distinct from end-user steering and never imply user authorization. */
    fun steerFromParent(text: String): Boolean = enqueueSteering(text, emptyList(), SteeringOrigin.PARENT_AGENT)

    private fun enqueueSteering(
        text: String,
        images: List<io.github.mangi.eta.agent.model.AgentModelClient.ModelImage>,
        origin: SteeringOrigin,
    ): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank() && images.isEmpty()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            pendingSteering = true
            steeringCounter += 1
            steeringMessages.addLast(SteeringInput(prompt, images.toList(), origin, steeringCounter))
            pauseCondition.signalAll()
        }
        return true
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst().also { pendingSteering = steeringMessages.isNotEmpty() } }
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text

    fun pollSteeringInputOrSeal(): SteeringInput? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { pendingSteering = steeringMessages.isNotEmpty(); return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = pendingSteering

    /** Used to defer stale child tool calls until the parent directive reaches the model. */
    val hasPendingParentDirective: Boolean
        get() = lock.withLock { steeringMessages.any { it.origin == SteeringOrigin.PARENT_AGENT } }

    /**
     * 排入下一条完整 turn 边界。run 已结束/已封口时返回 false，调用方据此改为排队或开新 run，
     * 不能把“已接收”当成“已投递”。
     */
    fun enqueueConversationMessage(input: ConversationInput): Boolean = lock.withLock {
        if (cancelled || !acceptingConversationMessages) return false
        conversationMessages.addLast(input)
        pauseCondition.signalAll()
        true
    }

    /** 请求前消费全部待注入消息；返回空表示没有新消息。 */
    fun pollConversationInputs(): List<ConversationInput> = lock.withLock {
        if (conversationMessages.isEmpty()) return emptyList()
        conversationMessages.toList().also { conversationMessages.clear() }
    }

    /**
     * 自然结束前原子地消费最后一批消息；队列为空则永久关闭本 run 的会话消息入口，
     * 避免 loop 已返回后仍把跨会话消息误报为已投递。
     */
    fun pollConversationInputsOrSeal(): List<ConversationInput> = lock.withLock {
        if (conversationMessages.isNotEmpty()) {
            return conversationMessages.toList().also { conversationMessages.clear() }
        }
        acceptingConversationMessages = false
        emptyList()
    }

    data class BoundaryInputs<T>(val steering: List<SteeringInput>, val conversations: List<ConversationInput>, val reports: List<T>)

    fun <T> collectBoundaryInputs(reports: () -> List<T>): BoundaryInputs<T> = lock.withLock {
        val readyReports = reports()
        val steering = steeringMessages.toList().also { steeringMessages.clear(); pendingSteering = false }
        val conversations = conversationMessages.toList().also { conversationMessages.clear() }
        BoundaryInputs(steering, conversations, readyReports)
    }

    /** Wait under the same lock as steering and wake notifications; never poll the model. */
    fun awaitWorkAvailable(isReady: () -> Boolean) = lock.withLock {
        while (!cancelled && (paused || (steeringMessages.isEmpty() && conversationMessages.isEmpty() && !isReady()))) {
            try {
                pauseCondition.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AgentRunCancelledException()
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    /** Wait once for an answer, plan update or steering, with a monotonic deadline. */
    fun awaitWorkAvailableFor(timeoutMs: Long, isReady: () -> Boolean): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cancelled && steeringMessages.isEmpty() && !isReady() && remaining > 0L) {
            try { remaining = pauseCondition.awaitNanos(remaining) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw AgentRunCancelledException() }
        }
        if (cancelled) throw AgentRunCancelledException()
        isReady()
    }

    fun signalWorkAvailable() = lock.withLock { pauseCondition.signalAll() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock {
            if (cancelled) return
            paused = true
            pauseCheckpointReached = false
            children.forEach { it.pause() }
        }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCheckpointReached = false
            children.forEach { it.resume() }
            pauseCondition.signalAll()
        }
    }

    /** Child checkpoints follow parent pause/resume; already-running I/O is not cancelled. */
    fun registerChild(child: AgentRunController): ResourceBinding {
        val alreadyCancelled = lock.withLock {
            if (cancelled) true else {
                children.add(child)
                if (paused) child.pause()
                false
            }
        }
        if (alreadyCancelled) child.cancel()
        return ResourceBinding { lock.withLock { children.remove(child) } }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                pauseCheckpointReached = true
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
