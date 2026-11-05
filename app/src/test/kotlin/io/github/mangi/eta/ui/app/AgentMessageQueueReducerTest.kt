package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun pollNextToSendAfterRunRespectsUserStopExceptForImmediateInterrupt() {
        val normalQueue = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val state = baseState(isStreaming = false).copy(queuedMessages = normalQueue)

        // 用户主动点击停止按钮：队列保留，不自动发出
        val (stoppedState, stoppedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = true,
        )
        assertNull(stoppedNext)
        assertEquals(2, stoppedState.queuedMessages.size)

        // 自然完成：自动弹出队首第 1 条
        val (completedState, completedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = false,
        )
        assertEquals("q-1", completedNext?.id)
        assertEquals(listOf("q-2"), completedState.queuedMessages.map { it.id })

        // 插话触发的中止：即使运行因取消结束，队首插话项仍立即弹出发送
        val interruptState = state.copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-interrupt", content = "立即插话", immediateInterrupt = true),
                QueuedMessageUi(id = "q-1", content = "第一条"),
            ),
        )
        val (afterInterruptState, interruptNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = interruptState,
            stoppedByUser = true,
        )
        assertEquals("q-interrupt", interruptNext?.id)
        assertFalse(interruptNext!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterInterruptState.queuedMessages.map { it.id })
    }

    @Test
    fun planEnqueueKeepsFifoAndCannotBecomeSteering() {
        val state = baseState(isStreaming = true).copy(
            draftRequestMode = "plan",
            queuedMessages = listOf(QueuedMessageUi("normal", "first")),
        )
        val queued = AgentMessageQueueReducer.enqueue(
            state,
            QueuedMessageUi("plan", "second", immediateInterrupt = true, requestMode = "plan"),
        )

        assertEquals(listOf("normal", "plan"), queued.queuedMessages.map { it.id })
        assertEquals("plan", queued.queuedMessages.last().requestMode)
        assertFalse(queued.queuedMessages.last().immediateInterrupt)
        assertEquals("normal", queued.draftRequestMode)
        for (compacting in listOf(false, true)) {
            val running = queued.copy(isCompacting = compacting)
            val (unchanged, steering) = AgentMessageQueueReducer.promoteToInterrupt(running, "plan")
            assertEquals(running, unchanged)
            assertNull(steering)
        }
    }

    @Test
    fun planCanBeSentAsANewRequestWhenIdle() {
        val plan = QueuedMessageUi("plan", "review", requestMode = "plan")
        val state = baseState(isStreaming = false).copy(queuedMessages = listOf(plan))

        val (next, dispatched) = AgentMessageQueueReducer.promoteToInterrupt(state, plan.id)

        assertTrue(next.queuedMessages.isEmpty())
        assertEquals(plan, dispatched)
        assertFalse(dispatched!!.immediateInterrupt)
    }

    @Test
    fun automaticPlanDispatchPreservesModeAttachmentsAndCurrentDraft() {
        val image = PendingImageUi("image", "uri", "data", "image/png")
        val file = PendingFileReferenceUi(
            "file",
            AgentFileReference("doc.txt", "/sdcard/doc.txt", AgentFileReferenceKind.File),
        )
        val plan = QueuedMessageUi(
            "plan", "review", listOf(image), listOf(file), requestMode = "plan",
        )
        val state = baseState(isStreaming = false, input = "unsent", images = listOf(image)).copy(
            draftRequestMode = "plan",
            pendingFileReferences = listOf(file),
            queuedMessages = listOf(plan, QueuedMessageUi("normal", "next")),
        )

        val (next, dispatched) = AgentMessageQueueReducer.pollNextToSendAfterRun(state, stoppedByUser = false)

        assertEquals(plan, dispatched)
        assertEquals("unsent", next.input)
        assertEquals("plan", next.draftRequestMode)
        assertEquals(listOf(image), next.pendingImages)
        assertEquals(listOf(file), next.pendingFileReferences)
        assertEquals(listOf("normal"), next.queuedMessages.map { it.id })
    }

    @Test
    fun stoppingDoesNotAutomaticallyDispatchQueuedPlan() {
        val plan = QueuedMessageUi("plan", "review", requestMode = "plan")
        val state = AgentMessageQueueReducer.enqueue(baseState(isStreaming = true), plan)

        val (next, dispatched) = AgentMessageQueueReducer.pollNextToSendAfterRun(state, stoppedByUser = true)

        assertNull(dispatched)
        assertEquals(listOf(plan), next.queuedMessages)
    }

    @Test
    fun recallingAttachmentOnlyPlanRestoresModeAndDeduplicatesAttachments() {
        val image = PendingImageUi("image", "uri", "data", "image/png")
        val file = PendingFileReferenceUi(
            "file",
            AgentFileReference("doc.txt", "/sdcard/doc.txt", AgentFileReferenceKind.File),
        )
        val state = baseState(isStreaming = true, images = listOf(image)).copy(
            pendingFileReferences = listOf(file),
            queuedMessages = listOf(
                QueuedMessageUi("plan", "", listOf(image), listOf(file), requestMode = "plan"),
            ),
            inputSyncVersion = 4,
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(state, "local text", "plan")!!

        assertEquals("local text", recalled.input)
        assertEquals("plan", recalled.draftRequestMode)
        assertEquals(listOf(image), recalled.pendingImages)
        assertEquals(listOf(file), recalled.pendingFileReferences)
        assertTrue(recalled.queuedMessages.isEmpty())
        assertEquals(5, recalled.inputSyncVersion)
    }

    @Test
    fun ordinaryRecallPreservesExplicitCurrentPlanDraft() {
        val state = baseState(isStreaming = true).copy(
            draftRequestMode = "plan",
            queuedMessages = listOf(QueuedMessageUi("normal", "queued text")),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(state, "draft", "normal", currentRequestMode = "plan")!!

        assertEquals("draft\nqueued text", recalled.input)
        assertEquals("plan", recalled.draftRequestMode)
    }

    @Test
    fun normalRecallUsesLiveModeInsteadOfOppositeStaleParentAndMergesAttachments() {
        val existingImage = PendingImageUi("existing-image", "u1", "d1", "image/png")
        val queuedImage = PendingImageUi("queued-image", "u2", "d2", "image/png")
        val existingFile = PendingFileReferenceUi(
            "existing-file", AgentFileReference("one.txt", "/sdcard/one.txt", AgentFileReferenceKind.File),
        )
        val queuedFile = PendingFileReferenceUi(
            "queued-file", AgentFileReference("two.txt", "/sdcard/two.txt", AgentFileReferenceKind.File),
        )
        for (currentMode in listOf("normal", "plan")) {
            val state = baseState(isStreaming = true, images = listOf(existingImage)).copy(
                draftRequestMode = if (currentMode == "normal") "plan" else "normal",
                pendingFileReferences = listOf(existingFile),
                queuedMessages = listOf(
                    QueuedMessageUi(
                        "normal", "queued text", listOf(existingImage, queuedImage),
                        listOf(existingFile, queuedFile), requestMode = "normal",
                    ),
                ),
                inputSyncVersion = 6,
            )

            val recalled = AgentMessageQueueReducer.recallToDraft(
                state, "live draft", "normal", currentRequestMode = currentMode,
            )!!

            assertEquals(currentMode, recalled.draftRequestMode)
            assertEquals("live draft\nqueued text", recalled.input)
            assertEquals(listOf(existingImage, queuedImage), recalled.pendingImages)
            assertEquals(listOf(existingFile, queuedFile), recalled.pendingFileReferences)
            assertTrue(recalled.queuedMessages.isEmpty())
            assertEquals(7, recalled.inputSyncVersion)
        }
    }

    @Test
    fun recalledPlanOverridesCurrentNormalModeAndRestoresAttachments() {
        val image = PendingImageUi("image", "uri", "data", "image/png")
        val file = PendingFileReferenceUi(
            "file", AgentFileReference("doc.txt", "/sdcard/doc.txt", AgentFileReferenceKind.File),
        )
        val state = baseState(isStreaming = true).copy(
            draftRequestMode = "normal",
            queuedMessages = listOf(
                QueuedMessageUi("plan", "review", listOf(image), listOf(file), requestMode = "plan"),
            ),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(
            state, "draft", "plan", currentRequestMode = "normal",
        )!!

        assertEquals("plan", recalled.draftRequestMode)
        assertEquals("draft\nreview", recalled.input)
        assertEquals(listOf(image), recalled.pendingImages)
        assertEquals(listOf(file), recalled.pendingFileReferences)
    }

    @Test
    fun legacyRecallDefaultsToNormalRatherThanStaleParentPlan() {
        val state = baseState(isStreaming = true).copy(
            draftRequestMode = "plan",
            queuedMessages = listOf(QueuedMessageUi("normal", "text")),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(state, "draft", "normal")!!

        assertEquals("normal", recalled.draftRequestMode)
        assertEquals("draft\ntext", recalled.input)
    }

    @Test
    fun submittingRecalledPlanClearsRestoreModeButDoesNotChangeQueuedSnapshots() {
        val image = PendingImageUi("image", "uri", "data", "image/png")
        val plan = QueuedMessageUi("plan", "review", pendingImages = listOf(image), requestMode = "plan")
        val state = baseState(isStreaming = false, input = "draft", images = listOf(image)).copy(
            draftRequestMode = "plan",
            queuedMessages = listOf(plan),
            inputSyncVersion = 9,
        )

        val cleared = AgentMessageQueueReducer.clearSubmittedDraft(state)

        assertEquals("normal", cleared.draftRequestMode)
        assertEquals("", cleared.input)
        assertTrue(cleared.pendingImages.isEmpty())
        assertTrue(cleared.pendingFileReferences.isEmpty())
        assertEquals(listOf(plan), cleared.queuedMessages)
        assertEquals(9, cleared.inputSyncVersion)
        assertEquals("plan", state.draftRequestMode)
        assertEquals(listOf(image), state.pendingImages)
    }

    @Test
    fun normalSteeringAndRemovalDoNotMutateAQueuedPlanMode() {
        val plan = QueuedMessageUi("plan", "review", requestMode = "plan")
        val state = baseState(isStreaming = true).copy(
            queuedMessages = listOf(plan, QueuedMessageUi("normal", "guidance")),
        )

        val (next, steering) = AgentMessageQueueReducer.promoteToInterrupt(state, "normal")

        assertEquals("normal", steering!!.requestMode)
        assertEquals(plan, next.queuedMessages.last())
        val removed = AgentMessageQueueReducer.remove(next, "normal")
        assertEquals(listOf(plan), removed.queuedMessages)
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
