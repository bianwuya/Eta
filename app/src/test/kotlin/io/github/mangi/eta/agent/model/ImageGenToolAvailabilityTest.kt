package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Test

class ImageGenToolAvailabilityTest {
    @Test fun configuredToolRespectsGlobalDisableSwitch() {
        fun names(disabled: Set<String>) = AgentToolCatalog.build(false, false,
            imageGeneration = true, disabledTools = disabled).let { tools ->
                (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            }
        org.junit.Assert.assertTrue("generate_image" in names(emptySet()))
        assertFalse("generate_image" in names(setOf("generate_image")))
    }
    @Test fun unconfiguredImageGenerationIsNotAdvertised() {
        val tools = AgentToolCatalog.build(false, false)
        assertFalse((0 until tools.length()).any {
            tools.getJSONObject(it).getJSONObject("function").getString("name") == "generate_image"
        })
    }
}
