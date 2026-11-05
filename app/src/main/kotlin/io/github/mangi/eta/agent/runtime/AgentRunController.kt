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

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    data class SteeringInput(val text: String, val images: List<io.github.mangi.eta.agent.model.AgentModelClient.ModelImage> = emptyList())

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
    private var acceptingSteering = true
    private var acceptingConversationMessages = true
    @Volatile
    private var paused = false

    val isPaused: Boolean
        get() = paused

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            acceptingConversationMessages = false
            steeringMessages.clear()
            conversationMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String, images: List<io.github.mangi.eta.agent.model.AgentModelClient.ModelImage> = emptyList()): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank() && images.isEmpty()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(SteeringInput(prompt, images.toList()))
            pauseCondition.signalAll()
        }
        return true
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst() }
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text

    fun pollSteeringInputOrSeal(): SteeringInput? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 排入下一条完整 turn 边界。run 已结束/已封口时返回 false，调用方据此改为排队或开新 run，
     * 不能把“已接收”当成“已投递”。
     */
    fun enqueueConversationMessage(input: ConversationInput): Boolean = lock.withLock {
        if (cancelled || !acceptingConversationMessages) return false
        conversationMessages.addLast(input)
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

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
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
