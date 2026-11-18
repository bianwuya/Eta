package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageQueueReducerTest {

    @Test
    fun enqueueAppendsInOrderAndClearsCurrentDraft() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val initial = baseState(
            isStreaming = true,
            input = "草稿",
            images = listOf(image),
        )

        val firstState = AgentMessageQueueReducer.enqueue(
            state = initial,
            item = QueuedMessageUi(id = "q-1", content = "第一条", pendingImages = listOf(image)),
        )
        val secondState = AgentMessageQueueReducer.enqueue(
            state = firstState,
            item = QueuedMessageUi(id = "q-2", content = "第二条"),
        )

        assertEquals("", secondState.input)
        assertTrue(secondState.pendingImages.isEmpty())
        assertEquals(listOf("q-1", "q-2"), secondState.queuedMessages.map { it.id })
    }

    @Test
    fun immediateInterruptPreemptsQueueHeadAndDemotesPreviousInterrupt() {
        val state = baseState(isStreaming = true).let {
            val s1 = AgentMessageQueueReducer.enqueue(it, QueuedMessageUi(id = "q-1", content = "普通排队"))
            AgentMessageQueueReducer.enqueue(s1, QueuedMessageUi(id = "q-2", content = "第一次插话", immediateInterrupt = true))
        }
        assertEquals(listOf("q-2", "q-1"), state.queuedMessages.map { it.id })
        assertTrue(state.queuedMessages[0].immediateInterrupt)

        val next = AgentMessageQueueReducer.enqueue(
            state = state,
            item = QueuedMessageUi(id = "q-3", content = "第二次插话", immediateInterrupt = true),
        )
        assertEquals(listOf("q-3", "q-2", "q-1"), next.queuedMessages.map { it.id })
        assertTrue(next.queuedMessages[0].immediateInterrupt)
        assertFalse(next.queuedMessages[1].immediateInterrupt)
    }

    @Test
    fun promoteToInterruptMovesItemToFrontWhenStreamingAndPopsImmediatelyWhenIdle() {
        val queued = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val streamingState = baseState(isStreaming = true).copy(queuedMessages = queued)
        val (promotedStreaming, promotedItem) = AgentMessageQueueReducer.promoteToInterrupt(streamingState, "q-2")
        assertNotNull(promotedItem)
        assertEquals(listOf("q-2", "q-1"), promotedStreaming.queuedMessages.map { it.id })
        assertTrue(promotedStreaming.queuedMessages.first().immediateInterrupt)

        val idleState = baseState(isStreaming = false).copy(queuedMessages = queued)
        val (afterIdlePop, poppedItem) = AgentMessageQueueReducer.promoteToInterrupt(idleState, "q-2")
        assertNotNull(poppedItem)
        assertFalse(poppedItem!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterIdlePop.queuedMessages.map { it.id })
    }

    @Test
    fun recallToDraftMergesWithUnsentTextAndAttachmentsAndBumpsSyncVersion() {
        val existingImage = PendingImageUi(id = "img-1", uri = "u1", dataUrl = "d1", mimeType = "image/png")
        val queuedImage = PendingImageUi(id = "img-2", uri = "u2", dataUrl = "d2", mimeType = "image/png")
        val queuedFile = PendingFileReferenceUi(
            id = "file-1",
            reference = AgentFileReference(
                displayName = "doc.txt",
                absolutePath = "/sdcard/doc.txt",
                kind = AgentFileReferenceKind.File,
            ),
        )
        val state = baseState(
            isStreaming = true,
            input = "",
            images = listOf(existingImage),
        ).copy(
            queuedMessages = listOf(
                QueuedMessageUi(
                    id = "q-1",
                    content = "撤回的内容",
                    pendingImages = listOf(existingImage, queuedImage),
                    pendingFileReferences = listOf(queuedFile),
                ),
                QueuedMessageUi(id = "q-2", content = "保留的内容"),
            ),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(
            state = state,
            currentInputText = "正在敲的半句话",
            id = "q-1",
        )
        assertNotNull(recalled)
        assertEquals("正在敲的半句话\n撤回的内容", recalled!!.input)
        assertEquals(listOf("img-1", "img-2"), recalled.pendingImages.map { it.id })
        assertEquals(listOf("file-1"), recalled.pendingFileReferences.map { it.id })
        assertEquals(listOf("q-2"), recalled.queuedMessages.map { it.id })
        assertEquals(state.inputSyncVersion + 1, recalled.inputSyncVersion)
    }

    @Test
    fun selectsNextMessageWithoutPoppingAndRespectsUserStop() {
        val head = QueuedMessageUi(id = "q-1", content = "第一条")
        val next = QueuedMessageUi(id = "q-2", content = "第二条")
        val state = baseState(isStreaming = false).copy(queuedMessages = listOf(head, next))
        assertNull(AgentMessageQueueReducer.nextToSendAfterRun(state, stoppedByUser = true))
        assertSame(head, AgentMessageQueueReducer.nextToSendAfterRun(state, stoppedByUser = false))
        assertEquals(listOf(head, next), state.queuedMessages)

        val interrupt = head.copy(immediateInterrupt = true)
        val interruptState = state.copy(queuedMessages = listOf(interrupt, next))
        assertSame(interrupt, AgentMessageQueueReducer.nextToSendAfterRun(interruptState, stoppedByUser = true))
        assertEquals(listOf(interrupt, next), interruptState.queuedMessages)
        assertNull(AgentMessageQueueReducer.nextToSendAfterRun(state.copy(isStreaming = true), stoppedByUser = false))
    }

    @Test
    fun preparingIsNonMutatingAndReusesTheSameRequestAndUserIds() {
        val item = QueuedMessageUi(id = "q-1", content = "等待权限校验")
        val state = baseState(isStreaming = false).copy(queuedMessages = listOf(item))
        val first = checkNotNull(AgentMessageQueueReducer.prepareDispatch(state, item.id))
        val retry = checkNotNull(AgentMessageQueueReducer.prepareDispatch(state, item.id))
        assertEquals("run-q-1", first.runId)
        assertEquals("user-run-q-1", first.userMessageId)
        assertEquals(first, retry)
        assertSame(item, state.queuedMessages.single())
        assertNull(AgentMessageQueueReducer.prepareDispatch(state.copy(isCompacting = true), item.id))
        assertNull(AgentMessageQueueReducer.prepareDispatch(state.copy(isConversationLoaded = false), item.id))
    }

    @Test
    fun failedWriteAheadRestoresOriginalItemWithoutOverwritingNewDraftOrOtherMessages() {
        val file = PendingFileReferenceUi("f1", AgentFileReference("Notes", "/workspace/notes.txt", AgentFileReferenceKind.File))
        val first = QueuedMessageUi(id = "q-first", content = "Earlier")
        val toSend = QueuedMessageUi("q-second", "With file", pendingFileReferences = listOf(file), immediateInterrupt = true)
        val justQueued = QueuedMessageUi(id = "q-new", content = "New")
        val initial = baseState(isStreaming = false).copy(queuedMessages = listOf(first, toSend))
        val dispatch = checkNotNull(AgentMessageQueueReducer.prepareDispatch(initial, toSend.id))
        val older = UserMessageUi("old", "Earlier message")
        val staged = UserMessageUi(dispatch.userMessageId, toSend.content)
        val history = listOf(older, staged).map { AgentModelClient.ConversationMessage("user", it.content, messageId = it.id) }
        val state = initial.copy(
            messages = listOf(older, staged), history = history, journal = history,
            input = "Keep new draft", isStreaming = true, queuedMessages = listOf(first, justQueued),
        )
        val restored = AgentMessageQueueReducer.restoreFailedDispatch(state, dispatch)
        assertEquals(listOf(first, toSend.copy(immediateInterrupt = false), justQueued), restored.queuedMessages)
        assertEquals(listOf(older), restored.messages)
        assertEquals(history.take(1), restored.history)
        assertEquals(restored.history, restored.journal)
        assertEquals(state.input, restored.input)
        assertFalse(restored.isStreaming)
        assertEquals(restored, AgentMessageQueueReducer.restoreFailedDispatch(restored, dispatch))
    }

    @Test
    fun promoteToQueueHeadEnqueueAtHeadAndClearInterruptFlagPreserveQueueWithoutInterrupt() {
        val initial = baseState(isStreaming = true).copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-1", content = "第一条"),
                QueuedMessageUi(id = "q-2", content = "第二条", immediateInterrupt = true),
            ),
        )
        val cleared = AgentMessageQueueReducer.clearInterruptFlag(initial, "q-2")
        assertFalse(cleared.queuedMessages[1].immediateInterrupt)

        val promotedHead = AgentMessageQueueReducer.promoteToQueueHead(cleared, "q-2")
        assertEquals(listOf("q-2", "q-1"), promotedHead.queuedMessages.map { it.id })
        assertFalse(promotedHead.queuedMessages.any { it.immediateInterrupt })

        val enqueuedHead = AgentMessageQueueReducer.enqueueAtHead(
            state = promotedHead.copy(input = "带图插话"),
            item = QueuedMessageUi(id = "q-3", content = "带图插话", immediateInterrupt = true),
        )
        assertEquals("", enqueuedHead.input)
        assertEquals(listOf("q-3", "q-2", "q-1"), enqueuedHead.queuedMessages.map { it.id })
        assertFalse(enqueuedHead.queuedMessages.first().immediateInterrupt)
    }

    @Test
    fun compactionRefusesSteeringWithoutMutatingTextAttachmentsOrQueueOrder() {
        val image = PendingImageUi("img-1", "file:///1.jpg", "data:image/jpeg;base64,AA==", "image/jpeg")
        val file = PendingFileReferenceUi("file-1", AgentFileReference("Notes", "/workspace/notes.txt", AgentFileReferenceKind.File))
        val queued = listOf(
            QueuedMessageUi("q-1", "先前消息"),
            QueuedMessageUi("q-2", "保留文字和附件", listOf(image), listOf(file)),
        )
        for (streaming in listOf(true, false)) {
            val initial = baseState(isStreaming = streaming, input = "未发送草稿")
                .copy(isCompacting = true, queuedMessages = queued)
            val (after, toDispatch) = AgentMessageQueueReducer.promoteToInterrupt(initial, "q-2")
            assertSame(initial, after)
            assertEquals(queued, after.queuedMessages)
            assertNull(toDispatch)
        }
    }

    private fun baseState(
        isStreaming: Boolean,
        input: String = "",
        images: List<PendingImageUi> = emptyList(),
    ) = AgentChatUiState(
        messages = emptyList(),
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = true,
        pendingImages = images,
    )
}
