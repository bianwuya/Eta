package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRuntimeRunExecutor
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageGenToolAvailabilityTest {
    @Test fun configuredToolRespectsGlobalDisableSwitch() {
        fun names(disabled: Set<String>) = AgentRuntimeRunExecutor.buildRunTools(
            mcpTools = JSONArray(),
            subagentAllowed = false,
            includeHistoryTool = false,
            includeCharacterMemoryTools = false,
            includeImageGenTool = disabled.isEmpty(),
        ).let { tools ->
                (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            }
        org.junit.Assert.assertTrue("generate_image" in names(emptySet()))
        assertFalse("generate_image" in names(setOf("generate_image")))
    }
    @Test fun unconfiguredImageGenerationIsNotAdvertised() {
        val tools = AgentRuntimeRunExecutor.buildRunTools(
            mcpTools = JSONArray(),
            subagentAllowed = false,
            includeHistoryTool = false,
            includeCharacterMemoryTools = false,
        )
        assertFalse((0 until tools.length()).any {
            tools.getJSONObject(it).getJSONObject("function").getString("name") == "generate_image"
        })
    }
}
