package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.RuntimeRunRegistry
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaAssistantRunLifecycleTest {
    @Test
    fun detachedEntryLeavesRuntimeAndWorkerAliveAndInvalidatesOldObserver() {
        val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
        val service = host.get()
        val request = AgentRuntimeWire.RunRequest("voice-a", "A",
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList())
        val registry = RuntimeRunRegistry()
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId)) }
            as RuntimeRunRegistry.Admission.Accepted).run
        val worker = Job()
        service.setField("activeRunId", run.runId)
        service.setField("observerGeneration", 3L)
        service.setField("runJob", worker)
        try {
            service.invoke("detachCurrentRun")
            assertNull(service.field("activeRunId"))
            assertEquals(4L, service.field("observerGeneration"))
            assertFalse(worker.isCancelled)
            assertFalse(run.session.controller.isCancelled)
            assertEquals(run.runId, registry.snapshots().single().runId)
            host.destroy()
            assertFalse(worker.isCancelled)
            assertFalse(run.session.controller.isCancelled)
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun newTasksUseDistinctConversationIdentitiesWhileContinueUsesOriginalIdentity() {
        val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
        try {
            val service = host.get()
            val keyA = service.invoke("newConversationKey") as String
            val keyB = service.invoke("newConversationKey") as String
            assertTrue(keyA.startsWith("eta_assistant_"))
            assertNotEquals(keyA, keyB)
            val original = AgentRuntimeWire.ActiveRunSnapshot("a", "conversation-a",
                handoffSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, conversationKey = keyA)
            assertEquals(keyA, original.conversationKey)
        } finally { host.destroy() }
    }

    private fun EtaAssistantOverlayService.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }
    private fun EtaAssistantOverlayService.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    private fun EtaAssistantOverlayService.invoke(name: String): Any? =
        javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
}
