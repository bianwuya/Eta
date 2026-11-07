package io.github.mangi.eta.local.planmode

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanModeWireTest {
    private fun request(mode: String) = AgentRuntimeWire.RunRequest(runId = "run", prompt = "prompt",
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
        images = emptyList(), requestMode = mode)
    @Test fun validModesRoundTripAndLegacyDefaultsToNormal() {
        for (mode in listOf("normal", "plan")) {
            val bundle = AgentRuntimeWire.toLegacyBundle(request(mode))
            assertEquals(mode, AgentRuntimeWire.runRequestFromBundle(bundle).requestMode)
            bundle.remove("request_mode")
            assertEquals("normal", AgentRuntimeWire.runRequestFromBundle(bundle).requestMode)
        }
    }
    @Test fun malformedModeIsRejectedAndCheckpointPayloadRemainsBackwardCompatible() {
        val bundle = AgentRuntimeWire.toLegacyBundle(request("normal"))
        bundle.putString("request_mode", "other")
        assertTrue(runCatching { AgentRuntimeWire.runRequestFromBundle(bundle) }.isFailure)
        val old = AgentUiHandoffPayload("chat")
        assertEquals("normal", AgentUiHandoffPayload.from(old.toJson()).requestMode)
        assertEquals("plan", AgentUiHandoffPayload.from(old.copy(requestMode = "plan").toJson()).requestMode)
        assertEquals("chat", AgentUiHandoffPayload.from(old.copy(requestMode = "plan").toJson()).conversationId)
    }
    @Test fun continuationLineageRoundTripsInWireAndCheckpointHandoff() {
        val planned = request("plan").copy(planLineageId = "old-run")
        val bundle = AgentRuntimeWire.toLegacyBundle(planned)
        val restored = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertEquals("plan", restored.requestMode)
        assertEquals("old-run", restored.planLineageId)
        val payload = AgentUiHandoffPayload("chat", requestMode = "plan", planLineageId = "old-run")
        assertEquals(payload, AgentUiHandoffPayload.from(payload.toJson()))
        assertTrue(runCatching { request("normal").copy(planLineageId = "old-run") }.isFailure)
        assertEquals("", AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toLegacyBundle(request("plan"))).planLineageId)
    }

}
