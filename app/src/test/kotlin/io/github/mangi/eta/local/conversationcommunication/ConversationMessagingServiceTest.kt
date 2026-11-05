package io.github.mangi.eta.local.conversationcommunication

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMessagingServiceTest {

    private class FakeHost : ConversationMessagingHost {
        val conversations = linkedMapOf<String, ConversationSnapshot>()
        val runStates = mutableMapOf<String, ConversationTargetRunState>()
        val deliveries = Collections.synchronizedList(mutableListOf<Pair<String, ConversationMessageEnvelope>>())
        val starts = Collections.synchronizedList(mutableListOf<ConversationMessageEnvelope>())
        var startResult: (ConversationMessageEnvelope) -> ConversationStartResult = { envelope ->
            ConversationStartResult.Started("run-started-${envelope.messageId}")
        }
        var deliverResult: (String) -> Boolean = { true }

        override fun conversation(conversationId: String): ConversationSnapshot? = conversations[conversationId]

        override fun conversations(limit: Int): List<ConversationSnapshot> =
            conversations.values.sortedByDescending { it.updatedAt }.take(limit)

        override fun runState(conversationId: String): ConversationTargetRunState =
            runStates[conversationId] ?: ConversationTargetRunState(ConversationTargetState.IDLE)

        override fun deliverIntoRun(runId: String, envelope: ConversationMessageEnvelope): Boolean {
            if (!deliverResult(runId)) return false
            deliveries += runId to envelope
            return true
        }

        override fun startRunWithMessage(
            target: ConversationSnapshot,
            envelope: ConversationMessageEnvelope,
        ): ConversationStartResult {
            starts += envelope
            return startResult(envelope)
        }
    }

    private class MemoryOutbox : ConversationMessageOutbox {
        private val lock = Any()
        private val entries = mutableListOf<ConversationOutboxEntry>()
        private val outcomes = mutableMapOf<String, ConversationOutcomeRecord>()
        var unavailableReason: String? = null
        var failRecordWrites = false
        var recordRejection: String? = null
        var updateFailure: ((ConversationOutboxEntry) -> Boolean)? = null

        fun storedEntries(): List<ConversationOutboxEntry> = synchronized(lock) { entries.toList() }

        fun storedOutcome(conversationId: String): ConversationTerminalOutcome? =
            synchronized(lock) { outcomes[conversationId]?.outcome }

        override fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

        override fun find(messageId: String): ConversationOutboxRead<ConversationOutboxEntry?> = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxRead.Unavailable(it) }
            ConversationOutboxRead.Known(entries.firstOrNull { it.messageId == messageId })
        }

        override fun pendingFor(targetConversationId: String): ConversationOutboxRead<List<ConversationOutboxEntry>> =
            synchronized(lock) {
                unavailableReason?.let { return ConversationOutboxRead.Unavailable(it) }
                ConversationOutboxRead.Known(
                    entries.filter {
                        it.targetConversationId == targetConversationId &&
                            it.state == ConversationDeliveryState.QUEUED
                    },
                )
            }

        override fun outcome(conversationId: String): ConversationOutboxRead<ConversationOutcomeRecord?> = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxRead.Unavailable(it) }
            ConversationOutboxRead.Known(outcomes[conversationId])
        }

        override fun record(entry: ConversationOutboxEntry): ConversationOutboxWrite = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxWrite.Failed(it) }
            if (failRecordWrites) return ConversationOutboxWrite.Failed("simulated write failure")
            recordRejection?.let { return ConversationOutboxWrite.Rejected(it, "record rejected") }
            entries.firstOrNull { it.messageId == entry.messageId }?.let {
                return ConversationOutboxWrite.Stored(it)
            }
            entries += entry
            ConversationOutboxWrite.Stored(entry)
        }

        override fun update(entry: ConversationOutboxEntry): ConversationOutboxWrite = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxWrite.Failed(it) }
            if (updateFailure?.invoke(entry) == true) return ConversationOutboxWrite.Failed("simulated update failure")
            val index = entries.indexOfFirst { it.messageId == entry.messageId }
            if (index < 0) return ConversationOutboxWrite.Rejected("CONVERSATION_MESSAGE_NOT_FOUND", "missing")
            entries[index] = entry
            ConversationOutboxWrite.Stored(entry)
        }

        override fun remove(messageId: String): ConversationOutboxWrite = synchronized(lock) {
            entries.removeAll { it.messageId == messageId }
            ConversationOutboxWrite.Stored(null)
        }

        override fun recordOutcome(record: ConversationOutcomeRecord): ConversationOutboxWrite = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxWrite.Failed(it) }
            outcomes[record.conversationId] = record
            ConversationOutboxWrite.Stored(null)
        }

        override fun clearOutcome(conversationId: String): ConversationOutboxWrite = synchronized(lock) {
            unavailableReason?.let { return ConversationOutboxWrite.Failed(it) }
            outcomes.remove(conversationId)
            ConversationOutboxWrite.Stored(null)
        }
    }

    private fun snapshot(id: String, title: String = "会话 $id", readOnly: Boolean = false) =
        ConversationSnapshot(id, title, updatedAt = 1_000L, modelId = null, readOnly = readOnly)

    private fun service(
        host: FakeHost,
        outbox: MemoryOutbox = MemoryOutbox(),
        allowSend: Boolean = true,
        sourceId: String = "conv-source",
    ) = ConversationMessagingService(
        sourceConversationId = sourceId,
        sourceTitle = "来源会话",
        sourceRunId = "run-source",
        host = host,
        outbox = outbox,
        allowSend = allowSend,
        now = { 1_760_000_000_000L },
    )

    private fun parsed(json: String): JSONObject = JSONObject(json)

    private fun hostWith(
        targetState: ConversationTargetRunState? = null,
        outcome: ConversationTerminalOutcome? = ConversationTerminalOutcome.COMPLETED,
    ): Pair<FakeHost, MemoryOutbox> {
        val host = FakeHost()
        host.conversations["conv-target"] = snapshot("conv-target")
        host.conversations["conv-source"] = snapshot("conv-source")
        targetState?.let { host.runStates["conv-target"] = it }
        val outbox = MemoryOutbox()
        outcome?.let {
            outbox.recordOutcome(ConversationOutcomeRecord("conv-target", it, "run-previous", 900L))
        }
        return host to outbox
    }

    @Test
    fun runningTargetReceivesTheMessageAtTheNextTurnBoundary() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        val result = parsed(service(host, outbox).send("conv-target", "请停止重复下载", "cm-aaaaaaaa"))

        assertTrue(result.getBoolean("ok"))
        assertEquals("accepted_in_run", result.getString("delivery"))
        assertEquals("run-live", result.getString("run_id"))
        assertFalse(result.getBoolean("queued"))
        assertFalse(result.getBoolean("outcome_unknown"))
        // 回执只说明已入队，不表示对方已阅读。
        assertTrue(result.getString("note").contains("不代表对方已阅读"))
        assertEquals(1, host.deliveries.size)
        assertTrue(host.deliveries.single().second.render().contains("不是终端用户发言"))
        assertEquals(ConversationDeliveryState.DELIVERED, outbox.storedEntries().single().state)
    }

    @Test
    fun idleTargetWithCompletedHistoryStartsANewRunOnTheExistingChain() {
        val (host, outbox) = hostWith()
        val result = parsed(service(host, outbox).send("conv-target", "构建结果已推送", "cm-bbbbbbbb"))

        assertEquals("started_new_run", result.getString("delivery"))
        assertEquals("run-started-cm-bbbbbbbb", result.getString("run_id"))
        assertFalse(result.getBoolean("queued"))
        assertEquals(1, host.starts.size)
        assertEquals(0, host.deliveries.size)
    }

    @Test
    fun idleTargetWithoutTerminalRecordIsQueuedNotWoken() {
        val (host, outbox) = hostWith(outcome = null)
        val result = parsed(service(host, outbox).send("conv-target", "在吗", "cm-cccccccc"))

        assertTrue(result.getBoolean("ok"))
        assertEquals("queued", result.getString("delivery"))
        assertTrue(result.getBoolean("queued"))
        assertTrue(result.getString("note").contains("不会"))
        assertEquals(0, host.starts.size)
        assertEquals(0, host.deliveries.size)
        assertEquals(ConversationDeliveryState.QUEUED, outbox.storedEntries().single().state)
    }

    @Test
    fun pausedOrStoppedTargetsAreQueuedWithoutStartingARun() {
        listOf(
            ConversationTargetRunState(ConversationTargetState.PAUSED, "run-paused") to
                ConversationDeliveryPolicy.TARGET_PAUSED,
            ConversationTargetRunState(ConversationTargetState.STOPPED, "run-stopped") to
                ConversationDeliveryPolicy.TARGET_STOPPED,
        ).forEach { (state, expectedReason) ->
            val (host, outbox) = hostWith(state)
            val result = parsed(service(host, outbox).send("conv-target", "继续", "cm-dddddddd"))
            assertEquals("queued", result.getString("delivery"))
            assertTrue(result.getString("note").contains(ConversationDeliveryPolicy.queueReasonText(expectedReason)))
            assertEquals(0, host.starts.size)
            assertEquals(0, host.deliveries.size)
        }
    }

    @Test
    fun sameMessageIdNeverDeliversTwice() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        val service = service(host, outbox)
        val first = parsed(service.send("conv-target", "同一条", "cm-eeeeeeee"))
        val second = parsed(service.send("conv-target", "同一条", "cm-eeeeeeee"))

        assertFalse(first.getBoolean("duplicate"))
        assertTrue(second.getBoolean("duplicate"))
        assertEquals("accepted_in_run", second.getString("delivery"))
        assertEquals(1, host.deliveries.size)
    }

    @Test
    fun sameMessageIdWithDifferentTargetOrTextIsAConflictNotSomeOldReceipt() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        host.conversations["conv-other"] = snapshot("conv-other")
        host.runStates["conv-other"] = ConversationTargetRunState(ConversationTargetState.RUNNING, "run-other")
        val service = service(host, outbox)

        service.send("conv-target", "原始正文", "cm-ffffffff")
        val differentText = parsed(service.send("conv-target", "被改过的正文", "cm-ffffffff"))
        val differentTarget = parsed(service.send("conv-other", "原始正文", "cm-ffffffff"))

        assertFalse(differentText.getBoolean("ok"))
        assertEquals("MESSAGE_ID_CONFLICT", differentText.getString("code"))
        assertFalse(differentTarget.getBoolean("ok"))
        assertEquals("MESSAGE_ID_CONFLICT", differentTarget.getString("code"))
        assertEquals(1, host.deliveries.size)
    }

    @Test
    fun concurrentSendWithTheSameIdDeliversExactlyOnce() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        val firstService = service(host, outbox)
        val secondService = service(host, outbox)
        val ready = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<JSONObject>())
        val threads = listOf(firstService, secondService).map { service ->
            thread {
                ready.await(2, TimeUnit.SECONDS)
                results += parsed(service.send("conv-target", "并发同一条", "cm-99999999"))
            }
        }
        ready.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(2, results.size)
        assertTrue(results.all { it.getBoolean("ok") })
        assertEquals(1, results.count { !it.getBoolean("duplicate") })
        assertEquals(1, host.deliveries.size)
    }

    @Test
    fun deliveryThatCannotBeRecordedReportsOutcomeUnknownAndIsNotRetried() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        outbox.updateFailure = { entry -> entry.state == ConversationDeliveryState.DELIVERED }
        val service = service(host, outbox)

        val first = parsed(service.send("conv-target", "只投一次", "cm-11111111"))
        assertTrue(first.getBoolean("ok"))
        assertEquals("accepted_in_run", first.getString("delivery"))
        assertFalse(first.getBoolean("receipt_persisted"))
        assertTrue(first.getBoolean("outcome_unknown"))
        assertTrue(first.getString("note").contains("不会被自动重投"))

        val retry = parsed(service.send("conv-target", "只投一次", "cm-11111111"))
        assertTrue(retry.getBoolean("duplicate"))
        assertEquals("outcome_unknown", retry.getString("delivery"))
        assertTrue(retry.getBoolean("outcome_unknown"))
        assertEquals(1, host.deliveries.size)
        assertEquals(ConversationDeliveryState.SENDING, outbox.storedEntries().single().state)
    }

    @Test
    fun reservationWriteFailureMeansNoDeliveryAndNoQueuedClaim() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        outbox.failRecordWrites = true
        val result = parsed(service(host, outbox).send("conv-target", "写盘会失败", "cm-22222222"))

        assertFalse(result.getBoolean("ok"))
        assertEquals(ConversationMessagingService.OUTCOME_UNKNOWN, result.getString("code"))
        assertEquals(0, host.deliveries.size)
        assertEquals(0, host.starts.size)
    }

    @Test
    fun unreadableStoreBlocksSendingInsteadOfPretendingQueued() {
        val (host, outbox) = hostWith()
        outbox.unavailableReason = "corrupt"
        val result = parsed(service(host, outbox).send("conv-target", "记录损坏时", "cm-33333333"))

        assertFalse(result.getBoolean("ok"))
        assertEquals("CONVERSATION_OUTBOX_UNAVAILABLE", result.getString("code"))
        assertEquals(0, host.deliveries.size)
    }

    @Test
    fun fullOutboxRejectionIsPassedThroughInsteadOfSilentQueueing() {
        val (host, outbox) = hostWith()
        outbox.recordRejection = "CONVERSATION_OUTBOX_FULL"
        val result = parsed(service(host, outbox).send("conv-target", "满了", "cm-44444444"))

        assertFalse(result.getBoolean("ok"))
        assertEquals("CONVERSATION_OUTBOX_FULL", result.getString("code"))
    }

    @Test
    fun runEndingBetweenCheckAndDeliveryFallsBackToARealStartOrQueue() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        host.deliverResult = { false }
        val started = parsed(service(host, outbox).send("conv-target", "刚好结束", "cm-55555555"))
        assertEquals("started_new_run", started.getString("delivery"))
        assertEquals(1, host.starts.size)

        val (rejectingHost, rejectingOutbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        rejectingHost.deliverResult = { false }
        rejectingHost.startResult = { ConversationStartResult.Rejected("CONVERSATION_BUSY", "busy") }
        val queued = parsed(service(rejectingHost, rejectingOutbox).send("conv-target", "刚好结束", "cm-66666666"))
        assertEquals("queued", queued.getString("delivery"))
        assertTrue(queued.getBoolean("queued"))
        assertEquals(1, rejectingHost.starts.size)
        assertEquals(ConversationDeliveryState.QUEUED, rejectingOutbox.storedEntries().single().state)
    }

    @Test
    fun queuedMessagesAreDeliveredAtTheNextRunAndNeverTwice() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        host.deliverResult = { false }
        host.startResult = { ConversationStartResult.Rejected("CONVERSATION_BUSY", "busy") }
        val service = service(host, outbox)
        service.send("conv-target", "排队消息", "cm-77777777")
        assertEquals(ConversationDeliveryState.QUEUED, outbox.storedEntries().single().state)

        host.deliverResult = { true }
        assertEquals(1, service.deliverQueued("conv-target", "run-next"))
        assertEquals(ConversationDeliveryState.DELIVERED, outbox.storedEntries().single().state)
        assertEquals(1, host.deliveries.size)
        assertEquals(0, service.deliverQueued("conv-target", "run-next"))
        assertEquals(1, host.deliveries.size)
    }

    @Test
    fun queuedMessageStaysUncertainWhenItsReceiptCannotBeWritten() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        host.deliverResult = { false }
        host.startResult = { ConversationStartResult.Rejected("CONVERSATION_BUSY", "busy") }
        val service = service(host, outbox)
        service.send("conv-target", "排队消息", "cm-88888888")

        host.deliverResult = { true }
        outbox.updateFailure = { entry -> entry.state == ConversationDeliveryState.DELIVERED }
        assertEquals(1, service.deliverQueued("conv-target", "run-next"))
        assertEquals(ConversationDeliveryState.SENDING, outbox.storedEntries().single().state)
        // SENDING 不是待投递状态：下一次运行不会再次自动投递。
        assertEquals(0, service.deliverQueued("conv-target", "run-next-2"))
        assertEquals(1, host.deliveries.size)
    }

    @Test
    fun originRunCannotSendMessagesToOtherConversations() {
        val (host, outbox) = hostWith()
        val result = parsed(service(host, outbox, allowSend = false).send("conv-target", "链式唤醒", "cm-12341234"))

        assertFalse(result.getBoolean("ok"))
        assertEquals("CONVERSATION_CHAIN_BLOCKED", result.getString("code"))
        assertEquals(0, host.starts.size)
        assertEquals(0, host.deliveries.size)
    }

    @Test
    fun listReportsAccurateTargetsAndDeliveryHints() {
        val (host, outbox) = hostWith(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-live"))
        host.conversations["conv-idle"] = snapshot("conv-idle", "空闲会话")
        val idleOutcome = ConversationOutcomeRecord("conv-idle", ConversationTerminalOutcome.COMPLETED, "run-old", 800L)
        outbox.recordOutcome(idleOutcome)

        val listed = parsed(service(host, outbox).list("", 10))
        assertTrue(listed.getBoolean("ok"))
        val items = listed.getJSONArray("conversations")
        val byId = (0 until items.length()).map(items::getJSONObject).associateBy { it.getString("conversation_id") }

        assertEquals("in_run", byId.getValue("conv-target").getString("delivery_hint"))
        assertEquals("run-live", byId.getValue("conv-target").getString("active_run_id"))
        assertEquals("start_run", byId.getValue("conv-idle").getString("delivery_hint"))
        assertTrue(byId.getValue("conv-source").getBoolean("is_current"))
        assertTrue(listed.getString("note").contains("conversation_id"))
    }
}
