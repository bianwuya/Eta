package io.github.mangi.eta.local.subagentcontext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskResultTest {
    private fun text(status: String, remaining: String = "-", summary: String = "Done", evidence: String = "Source.kt:42") =
        "task_status: $status\nsummary: $summary\nevidence: $evidence\nremaining: $remaining\nnext_action: -"

    @Test fun allFiveStatusesAreIndependentFromLifecycle() {
        SubagentTaskResult.Status.entries.forEach { status ->
            val pending = if (status in setOf(SubagentTaskResult.Status.PARTIAL, SubagentTaskResult.Status.BLOCKED)) "Verify device" else "-"
            val result = SubagentTaskResult.fromFinal(text(status.wire, pending))
            assertEquals(status, result.status)
            assertEquals("model", result.source)
            val envelope = result.attach(JSONObject().put("ok", true).put("status", "completed"))
            assertEquals(status == SubagentTaskResult.Status.COMPLETED, SubagentTaskResult.succeeded(envelope))
            assertEquals(status, SubagentTaskResult.fromEnvelope(envelope).status)
        }
    }

    @Test fun oldNaturalLanguageAndLifecycleOnlyRemainUnknown() {
        assertEquals(SubagentTaskResult.Status.UNKNOWN, SubagentTaskResult.fromFinal("Finished work").status)
        assertEquals(SubagentTaskResult.Status.UNKNOWN, SubagentTaskResult.fromFinal("Task is blocked").status)
        assertFalse(SubagentTaskResult.succeeded(JSONObject().put("ok", true).put("status", "completed")))
        assertFalse(SubagentTaskResult.succeeded(JSONObject().put("ok", true).put("task_status", "completed").put("status", "completed")))
    }

    @Test fun incompleteAndContradictoryReportsNeverComplete() {
        val reports = listOf(text("completed", "Still testing"), text("completed", evidence = "-"),
            text("blocked"), text("partial"), text("bogus"), text("completed", summary = ""),
            text("completed").replace("summary: Done\n", ""),
            text("completed") + "\ntask_status: completed", "```text\n${text("completed")}\n```",
            "Quoted example:\n${text("completed")}", text("completed").replace("evidence:", "remaining:"))
        reports.forEach { assertEquals(it, SubagentTaskResult.Status.UNKNOWN, SubagentTaskResult.fromFinal(it).status) }
        assertEquals(SubagentTaskResult.Status.UNKNOWN, SubagentTaskResult.fromFinal(text("completed"), false).status)
    }

    @Test fun unicodeProjectionIsBoundedWithoutBreakingSurrogates() {
        val raw = text("partial", "R".repeat(4_000), "\uD83D\uDE00".repeat(2_000), "E".repeat(5_000))
            .replace("next_action: -", "next_action: " + "N".repeat(2_000))
        val result = SubagentTaskResult.fromFinal(raw)
        assertEquals(SubagentTaskResult.Status.PARTIAL, result.status)
        assertEquals(SubagentTaskResult.MAX_SUMMARY, result.summary.codePointCount(0, result.summary.length))
        assertFalse(result.summary.last().isHighSurrogate())
        assertEquals(SubagentTaskResult.MAX_EVIDENCE, result.evidence.length)
        assertEquals(SubagentTaskResult.MAX_REMAINING, result.remaining.length)
        assertEquals(SubagentTaskResult.MAX_NEXT_ACTION, result.nextAction.length)
        assertTrue(result.truncated)
        assertEquals(raw, result.attach(JSONObject().put("summary", raw)).getString("summary"))
    }

    @Test fun cancellationAndRuntimeFailureCannotReuseACompletedClaim() {
        val result = SubagentTaskResult.fromFinal(text("completed"))
        listOf("cancelled", "interrupted").forEach { state ->
            val envelope = result.attach(JSONObject().put("ok", true).put("status", state))
            assertEquals(SubagentTaskResult.Status.UNKNOWN, SubagentTaskResult.fromEnvelope(envelope).status)
            assertFalse(SubagentTaskResult.succeeded(envelope))
        }
        val failed = result.attach(JSONObject().put("ok", false).put("status", "failed").put("message", "failure"))
        assertEquals(SubagentTaskResult.Status.FAILED, SubagentTaskResult.fromEnvelope(failed).status)
        assertFalse(SubagentTaskResult.succeeded(failed))
    }

    @Test fun malformedMetadataDoesNotReleaseDependencies() {
        val result = SubagentTaskResult.fromFinal(text("completed"))
        val envelope = result.attach(JSONObject().put("ok", true).put("status", "completed"))
        envelope.getJSONObject("result").put("summary", JSONObject.NULL)
        assertFalse(SubagentTaskResult.succeeded(envelope))
        val mismatched = result.attach(JSONObject().put("ok", true).put("status", "completed"))
        mismatched.put("task_status", "blocked")
        assertFalse(SubagentTaskResult.succeeded(mismatched))
    }
}
