package io.github.mangi.eta.local.subagentdisplay

import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentStepSnapshot
import io.github.mangi.eta.ui.components.subagentStepKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFixesDisplayIntegrationTest {
    @Test
    fun boundedTransportKeepsInspectorLazyKeysUniqueForLongToolCallIds() {
        // Provider tool-call IDs are not length-limited before StepTracker uses them.
        // The inspector now requires uniqueness after the existing bounded wire encoder.
        val snapshot = SubagentActivitySnapshot(
            subagentId = "child",
            steps = (0 until 12).map { index ->
                SubagentStepSnapshot(
                    id = "tool-1-" + "x".repeat(1000) + "-$index",
                    kind = SubagentStepSnapshot.KIND_TOOL,
                    title = "command",
                    status = SubagentStepSnapshot.STATUS_SUCCESS,
                )
            },
        )
        val encoded = snapshot.encodeForWire(12_000)
        assertTrue(encoded.length <= 12_000)
        val decoded = requireNotNull(SubagentActivitySnapshot.decode(encoded))
        val keys = subagentStepKeys(decoded.steps)
        assertEquals("Bounded transport must preserve unique inspector keys", keys.size, keys.toSet().size)
    }
    @Test
    fun boundedIdsRetainReadablePrefixAndRemainUniqueWithinPayloadBudget() {
        val snapshot = SubagentActivitySnapshot(subagentId = "child", steps = (1..100).map { index ->
            SubagentStepSnapshot(id = "step-$index" + "x".repeat(2000),
                kind = SubagentStepSnapshot.KIND_TOOL, title = "标题".repeat(2000),
                toolName = "read_file".repeat(2000))
        })
        val encoded = snapshot.encodeForWire(16_000)
        assertTrue(encoded.length <= 16_000)
        val decoded = requireNotNull(SubagentActivitySnapshot.decode(encoded))
        assertTrue(decoded.steps.last().id.startsWith("step-100"))
        val keys = subagentStepKeys(decoded.steps)
        assertEquals(keys.size, keys.toSet().size)
    }

}
