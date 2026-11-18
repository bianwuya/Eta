package io.github.mangi.eta.local.subagent

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentPriorityOrderTest {
    private val a = Model("a", "wire-a", "A", toolCall = true)
    private val b = Model("b", "wire-b", "B", toolCall = true)
    private val c = Model("c", "wire-c", "C", toolCall = true)
    private val provider = OpenAiCompatibleProviderSetting(
        id = "fixture-provider",
        name = "Fixture",
        baseUrl = "https://fixture.invalid/v1",
        models = listOf(a, b, c),
    )
    private val legacy = Json.decodeFromString<SubagentSettings>("""{"modelId":"b","reasoningEffort":"high"}""")
    private val source = subagentPriorityOrder(listOf("a", "b", "c"), legacy.modelPriority, legacy.modelId)
    private val heights = mapOf("a" to 60, "b" to 120, "c" to 90)

    @Test
    fun lightDragDoesNotSaveOrReplaceTheExplicitOldModel() {
        assertEquals(emptyList<String>(), legacy.modelPriority)
        assertEquals(listOf("b", "a", "c"), source)
        val drag = SubagentPriorityDrag(source, "b")
        drag.dragBy(29f, heights)
        assertNull(drag.finish())
        val fallback = RuntimeConfigRepository.buildRuntimeConfig(provider, b)
        assertNull(RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, provider, legacy.modelPriority))
        assertEquals("wire-b", fallback.model)
    }

    @Test
    fun draggingBackAcrossUnequalRowsDoesNotSave() {
        val drag = SubagentPriorityDrag(source, "b")
        drag.dragBy(115f, heights)
        assertEquals(listOf("a", "c", "b"), drag.order)
        drag.dragBy(-115f, heights)
        assertEquals(source, drag.order)
        assertNull(drag.finish())
    }

    @Test
    fun netReorderEmitsOnceAndPreservesTheOrdinaryModelAndEffort() {
        val drag = SubagentPriorityDrag(source, "a")
        drag.dragBy(-61f, heights)
        val writes = List(2) { drag.finish() }.filterNotNull()
        assertEquals(listOf(listOf("a", "b", "c")), writes)

        val previous = legacy.copy(modelPriority = listOf("gone"))
        val updated = previous.withModelPriority(writes.single())
        assertEquals(previous.copy(modelPriority = listOf("a", "b", "c", "gone")), updated)
        assertEquals(updated, Json.decodeFromString<SubagentSettings>(Json.encodeToString(updated)))
        val fallback = RuntimeConfigRepository.buildRuntimeConfig(provider, b)
        assertEquals("wire-a", RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, provider, updated.modelPriority)?.model)
    }

    @Test
    fun staleUnavailableAndOtherConnectionEntriesDoNotReplaceTheFallback() {
        val disabled = a.copy(id = "disabled", isEnabled = false)
        val noTools = a.copy(id = "no-tools", toolCall = false)
        val current = provider.copy(models = listOf(disabled, noTools, b, c))
        val fallback = RuntimeConfigRepository.buildRuntimeConfig(current, b)
        val unavailable = listOf("missing", "disabled", "no-tools", "other-provider-model")
        assertNull(RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, current, unavailable))
        assertEquals("wire-c", RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, current, unavailable + "c")?.model)
        assertNull(RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, current.copy(apiKey = "changed"), listOf("c")))
        assertNull(RuntimeConfigRepository.resolveSubagentPriorityConfig(fallback, current.copy(id = "other"), listOf("c")))
        assertEquals(listOf("c", "b"), subagentPriorityOrder(listOf("b", "c"), unavailable + listOf("c", "c"), "b"))
    }
}
