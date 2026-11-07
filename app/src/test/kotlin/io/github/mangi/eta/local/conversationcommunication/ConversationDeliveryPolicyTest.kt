package io.github.mangi.eta.local.conversationcommunication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDeliveryPolicyTest {
    private fun decide(
        target: ConversationTargetRunState,
        lastOutcome: ConversationTerminalOutcome? = ConversationTerminalOutcome.COMPLETED,
        targetExists: Boolean = true,
        targetId: String = "conv-target",
        readOnly: Boolean = false,
    ) = ConversationDeliveryPolicy.decide(
        sourceConversationId = "conv-source",
        targetConversationId = targetId,
        targetExists = targetExists,
        target = target,
        lastOutcome = lastOutcome,
        targetReadOnly = readOnly,
    )

    @Test
    fun runningTargetIsDeliveredIntoTheExistingRun() {
        assertEquals(
            ConversationDelivery.IntoRun("run-1"),
            decide(ConversationTargetRunState(ConversationTargetState.RUNNING, "run-1")),
        )
    }

    @Test
    fun runningTargetWithoutRunIdFallsBackToQueueOnly() {
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_RUN_UNAVAILABLE),
            decide(ConversationTargetRunState(ConversationTargetState.RUNNING)),
        )
    }

    @Test
    fun pausedTargetIsOnlyQueuedNeverAutoResumed() {
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_PAUSED),
            decide(ConversationTargetRunState(ConversationTargetState.PAUSED, "run-1")),
        )
    }

    @Test
    fun explicitStoppedTargetIsOnlyQueued() {
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_STOPPED),
            decide(ConversationTargetRunState(ConversationTargetState.STOPPED, "run-1")),
        )
    }

    @Test
    fun idleTargetWithCompletedOrFailedLastRunMayStartANewRun() {
        val idle = ConversationTargetRunState(ConversationTargetState.IDLE)
        assertEquals(ConversationDelivery.StartRun, decide(idle, ConversationTerminalOutcome.COMPLETED))
        assertEquals(ConversationDelivery.StartRun, decide(idle, ConversationTerminalOutcome.FAILED))
    }

    @Test
    fun idleTargetWithStoppedOutcomeIsOnlyQueued() {
        val idle = ConversationTargetRunState(ConversationTargetState.IDLE)
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_STOPPED),
            decide(idle, ConversationTerminalOutcome.STOPPED),
        )
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_PAUSED),
            decide(idle, ConversationTerminalOutcome.PAUSED),
        )
    }

    @Test
    fun unknownTerminalStateIsQueuedInsteadOfGuessed() {
        assertEquals(
            ConversationDelivery.QueueOnly(ConversationDeliveryPolicy.TARGET_STATE_UNKNOWN),
            decide(ConversationTargetRunState(ConversationTargetState.IDLE), lastOutcome = null),
        )
    }

    @Test
    fun selfAndUnknownTargetsAreRejected() {
        assertTrue(
            decide(ConversationTargetRunState(ConversationTargetState.IDLE), targetId = "conv-source")
                is ConversationDelivery.Reject,
        )
        assertTrue(
            decide(ConversationTargetRunState(ConversationTargetState.IDLE), targetExists = false)
                is ConversationDelivery.Reject,
        )
    }

    @Test
    fun archivedOrVoiceConversationsAreRejectedInsteadOfWoken() {
        val decision = decide(
            ConversationTargetRunState(ConversationTargetState.IDLE),
            readOnly = true,
        )
        assertEquals(ConversationDelivery.Reject("TARGET_READ_ONLY", "目标是只读归档或语音会话，不能接收跨会话消息"), decision)
    }

    @Test
    fun queueReasonsExplainThatNothingWasResumed() {
        listOf(
            ConversationDeliveryPolicy.TARGET_PAUSED,
            ConversationDeliveryPolicy.TARGET_STOPPED,
            ConversationDeliveryPolicy.TARGET_STATE_UNKNOWN,
            ConversationDeliveryPolicy.TARGET_RUN_UNAVAILABLE,
        ).forEach { reason ->
            val text = ConversationDeliveryPolicy.queueReasonText(reason)
            assertTrue("$reason should say the message is queued", text.contains("排队"))
            assertTrue("$reason must not promise delivery", !text.contains("已送达"))
        }
    }
}
