package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerConversationInputTest {
    private fun input(id: String) = AgentRunController.ConversationInput(
        messageId = id,
        sourceConversationId = "conv-source",
        sourceTitle = "来源",
        sourceRunId = "run-source",
        createdAt = 1L,
        envelopeText = "信封 $id",
    )

    @Test
    fun queuedConversationMessagesAreConsumedInOrderAtTheBoundary() {
        val controller = AgentRunController()
        assertTrue(controller.enqueueConversationMessage(input("cm-1")))
        assertTrue(controller.enqueueConversationMessage(input("cm-2")))

        assertEquals(listOf("cm-1", "cm-2"), controller.pollConversationInputs().map { it.messageId })
        assertTrue(controller.pollConversationInputs().isEmpty())
    }

    @Test
    fun emptyPollAtTheFinishBoundarySealsFurtherMessages() {
        val controller = AgentRunController()
        assertTrue(controller.pollConversationInputsOrSeal().isEmpty())
        assertFalse(controller.enqueueConversationMessage(input("cm-3")))
    }

    @Test
    fun pendingMessageIsDrainedBeforeSealing() {
        val controller = AgentRunController()
        assertTrue(controller.enqueueConversationMessage(input("cm-4")))
        assertEquals(listOf("cm-4"), controller.pollConversationInputsOrSeal().map { it.messageId })
        // 已经取到消息时不封口，下一轮仍可继续接收。
        assertTrue(controller.enqueueConversationMessage(input("cm-5")))
    }

    @Test
    fun cancellationClearsAndRejectsConversationMessages() {
        val controller = AgentRunController()
        assertTrue(controller.enqueueConversationMessage(input("cm-6")))
        controller.cancel()
        assertTrue(controller.pollConversationInputs().isEmpty())
        assertFalse(controller.enqueueConversationMessage(input("cm-7")))
    }

    @Test
    fun steeringQueueIsIndependentFromConversationMessages() {
        val controller = AgentRunController()
        assertTrue(controller.steer("补充"))
        assertTrue(controller.enqueueConversationMessage(input("cm-8")))
        assertEquals("补充", controller.pollSteeringMessage())
        assertEquals(listOf("cm-8"), controller.pollConversationInputs().map { it.messageId })
        assertFalse(controller.hasPendingSteering)
    }
}
