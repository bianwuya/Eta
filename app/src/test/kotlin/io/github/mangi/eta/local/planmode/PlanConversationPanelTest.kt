package io.github.mangi.eta.local.planmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlanConversationPanelTest {
    private val plan = PlanRecord("plan-a", "conversation", "run", 3, "title", "content",
        "awaiting_approval", "planning", true, listOf(
            PlanStep("read", "Read", actions = listOf(PlanAction("read_file", "{}"))),
            PlanStep("write", "Write", actions = listOf(PlanAction("write_file", "{\"path\":\"exact\"}"))),
            PlanStep("note", "No actions")))

    @Test fun unchangedRevisionAllowsAnExactFullLengthDraft() {
        assertNull(planEditValidation(3, 3, "x".repeat(40000)))
    }

    @Test fun staleDraftCannotOverwriteANewerRevision() {
        assertNotNull(planEditValidation(3, 4, "retained draft"))
        assertEquals(planEditValidation(3, 4, "retained draft"), planEditValidation(3, 4, ""))
    }

    @Test fun blankOrOversizedContentCannotBeSubmitted() {
        assertNotNull(planEditValidation(3, 3, " \n\t"))
        assertNotNull(planEditValidation(3, 3, "x".repeat(40001)))
    }

    @Test fun everyStepWithActionsMustHaveBeenExposed() {
        assertEquals(2, unreviewedPlanSteps(plan, "plan-a", 3, emptySet()))
        assertEquals(1, unreviewedPlanSteps(plan, "plan-a", 3, setOf("read")))
        assertEquals(0, unreviewedPlanSteps(plan, "plan-a", 3, setOf("read", "write")))
    }

    @Test fun newRevisionInvalidatesAllExposureEvidence() {
        assertEquals(2, unreviewedPlanSteps(plan.copy(revision = 4), "plan-a", 3, setOf("read", "write")))
    }

    @Test fun anotherPlanCannotReuseTheExposureEvidence() {
        assertEquals(2, unreviewedPlanSteps(plan.copy(planId = "plan-b"), "plan-a", 3, setOf("read", "write")))
    }
}
