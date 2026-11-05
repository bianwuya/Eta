package io.github.mangi.eta.local.conversationcommunication

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationMessageFileOutboxTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun entry(
        id: String,
        target: String = "conv-target",
        state: ConversationDeliveryState = ConversationDeliveryState.QUEUED,
        createdAt: Long = 1_000L,
    ) = ConversationOutboxEntry(
        messageId = id,
        sourceConversationId = "conv-source",
        sourceTitle = "来源",
        sourceRunId = "run-source",
        targetConversationId = target,
        text = "消息 $id",
        createdAt = createdAt,
        state = state,
        delivery = if (state == ConversationDeliveryState.QUEUED) "" else "accepted_in_run",
    )

    private fun outbox(file: File = File(temporary.root, "conversation-communication/outbox.json")) =
        ConversationMessageFileOutbox(file)

    @Test
    fun entriesAndOutcomesSurviveANewInstance() {
        val first = outbox()
        assertTrue(first.record(entry("cm-aaaaaaaa")) is ConversationOutboxWrite.Stored)
        assertTrue(
            first.recordOutcome(
                ConversationOutcomeRecord("conv-target", ConversationTerminalOutcome.STOPPED, "run-1", 10L),
            ) is ConversationOutboxWrite.Stored,
        )

        val second = outbox()
        assertEquals("cm-aaaaaaaa", (second.find("cm-aaaaaaaa") as ConversationOutboxRead.Known).value?.messageId)
        assertEquals(ConversationDeliveryState.QUEUED, (second.find("cm-aaaaaaaa") as ConversationOutboxRead.Known).value?.state)
        assertEquals(1, (second.pendingFor("conv-target") as ConversationOutboxRead.Known).value.size)
        assertEquals(
            ConversationTerminalOutcome.STOPPED,
            (second.outcome("conv-target") as ConversationOutboxRead.Known).value?.outcome,
        )
    }

    @Test
    fun duplicateMessageIdKeepsTheFirstEntry() {
        val store = outbox()
        store.record(entry("cm-bbbbbbbb", target = "conv-a"))
        val stored = store.record(entry("cm-bbbbbbbb", target = "conv-b")) as ConversationOutboxWrite.Stored
        assertEquals("conv-a", stored.entry?.targetConversationId)
        assertEquals(1, (store.pendingFor("conv-a") as ConversationOutboxRead.Known).value.size)
        assertEquals(0, (store.pendingFor("conv-b") as ConversationOutboxRead.Known).value.size)
    }

    @Test
    fun corruptFileIsReportedInsteadOfTreatedAsEmpty() {
        val file = File(temporary.root, "conversation-communication/outbox.json")
        file.parentFile!!.mkdirs()
        file.writeText("{not json")

        val store = outbox(file)
        assertTrue(store.find("cm-cccccccc") is ConversationOutboxRead.Unavailable)
        assertTrue(store.pendingFor("conv-target") is ConversationOutboxRead.Unavailable)
        assertTrue(store.outcome("conv-target") is ConversationOutboxRead.Unavailable)
        // 损坏时不得把新消息当成已排队，也不得覆盖原文件。
        assertTrue(store.record(entry("cm-cccccccc")) is ConversationOutboxWrite.Failed)
        assertEquals("{not json", file.readText())
    }

    @Test
    fun unwritableTargetIsReportedAsFailureNotAsQueued() {
        val directory = File(temporary.root, "conversation-communication/outbox.json")
        directory.mkdirs()
        val store = outbox(directory)
        assertTrue(store.record(entry("cm-dddddddd")) is ConversationOutboxWrite.Failed)
    }

    @Test
    fun fullOutboxRejectsNewMessagesInsteadOfDroppingQueuedOnes() {
        val store = outbox()
        repeat(MAX_ENTRIES) { index ->
            assertTrue(store.record(entry("cm-${index.toString().padStart(8, '0')}", createdAt = index.toLong()))
                is ConversationOutboxWrite.Stored)
        }
        val rejected = store.record(entry("cm-overflow", createdAt = 9_999L))
        assertEquals("CONVERSATION_OUTBOX_FULL", (rejected as ConversationOutboxWrite.Rejected).code)
        assertEquals(MAX_ENTRIES, (store.pendingFor("conv-target") as ConversationOutboxRead.Known).value.size)
        assertNull((store.find("cm-overflow") as ConversationOutboxRead.Known).value)
    }

    @Test
    fun terminalEntriesAreEvictedBeforeQueuedOnes() {
        val store = outbox()
        repeat(MAX_ENTRIES - 1) { index ->
            store.record(entry("cm-${index.toString().padStart(8, '0')}", createdAt = index.toLong()))
        }
        store.record(entry("cm-delivered", createdAt = 5_000L, state = ConversationDeliveryState.DELIVERED))

        val stored = store.record(entry("cm-new", createdAt = 9_000L))
        assertTrue(stored is ConversationOutboxWrite.Stored)
        assertNull((store.find("cm-delivered") as ConversationOutboxRead.Known).value)
        assertEquals(MAX_ENTRIES, (store.pendingFor("conv-target") as ConversationOutboxRead.Known).value.size)
    }

    @Test
    fun updateOfMissingEntryIsRejectedAndClearOutcomeRemovesOnlyThatConversation() {
        val store = outbox()
        store.recordOutcome(ConversationOutcomeRecord("conv-a", ConversationTerminalOutcome.PAUSED, "run-a", 1L))
        store.recordOutcome(ConversationOutcomeRecord("conv-b", ConversationTerminalOutcome.COMPLETED, "run-b", 2L))

        val missing = store.update(entry("cm-missing", state = ConversationDeliveryState.DELIVERED))
        assertEquals("CONVERSATION_MESSAGE_NOT_FOUND", (missing as ConversationOutboxWrite.Rejected).code)

        store.clearOutcome("conv-a")
        assertNull((store.outcome("conv-a") as ConversationOutboxRead.Known).value)
        assertEquals(
            ConversationTerminalOutcome.COMPLETED,
            (store.outcome("conv-b") as ConversationOutboxRead.Known).value?.outcome,
        )
    }

    @Test
    fun atomicReplacementLeavesNoTemporaryFileBehind() {
        val file = File(temporary.root, "conversation-communication/outbox.json")
        val store = outbox(file)
        store.record(entry("cm-eeeeeeee"))
        val leftovers = file.parentFile!!.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
        assertTrue(file.isFile)
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
