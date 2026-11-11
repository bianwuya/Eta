package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Test

class ImageGenToolAvailabilityTest {
    @Test fun unconfiguredImageGenerationIsNotAdvertised() {
        val tools = AgentToolCatalog.build(false, false)
        assertFalse((0 until tools.length()).any {
            tools.getJSONObject(it).getJSONObject("function").getString("name") == "generate_image"
        })
    }
}
