package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.RuntimeRunRegistry
import io.github.mangi.eta.agent.runtime.RuntimeMessengerFixture
import io.github.mangi.eta.agent.runtime.AgentEvent
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
    fun continueReallyAttachesAndDestroyDetachesWithoutStoppingRuntime() {
        RuntimeMessengerFixture().use { fixture ->
            val run = fixture.admit(fixture.request("voice-continue", "original-key"))
            run.prepared(run.request)
            run.session.emit(AgentEvent.RoundStarted(1, 1))
            // Establish the shared real Messenger binding before the overlay's client uses it.
            fixture.result(fixture.worker { fixture.client.queryActiveRuns() })
            val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
            val service = host.get()
            service.javaClass.getDeclaredMethod("continueVoiceRun", AgentRuntimeWire.ActiveRunSnapshot::class.java)
                .apply { isAccessible = true }.invoke(service, run.snapshot())
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }
            assertEquals("original-key", service.field("conversationKey"))
            assertEquals(run.runId, service.field("activeRunId"))
            assertFalse(AgentRuntimeWire.MSG_START_RUN in fixture.messages)
            host.destroy()
            fixture.await { AgentRuntimeWire.MSG_DETACH_RUN in fixture.messages }
            assertFalse(run.session.controller.isCancelled)
            assertTrue(fixture.registry.contains(run))
            fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "continues after UI destruction"))
        }
    }

    @Test
    fun repeatedIdentifiedIntentPreservesDraftEvenBeforeAWindowCanAttach() {
        val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
        try {
            val service = host.get()
            // Missing permission follows the real early-return path, after recording invocation identity.
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
            // Use the public show factory's actual intent, not an assumed action/extra contract.
            EtaAssistantOverlayService.show(service, null)
            val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService
            service.onStartCommand(requireNotNull(started), 0, 1)
            val input = service.javaClass.getDeclaredField("inputText\$delegate").apply { isAccessible = true }
                .get(service) as androidx.compose.runtime.MutableState<*>
            @Suppress("UNCHECKED_CAST")
            (input as androidx.compose.runtime.MutableState<String>).value = "keep this draft"
            val key = service.field("conversationKey")
            service.onStartCommand(started, 0, 2)
            assertEquals("keep this draft", input.value)
            assertEquals(key, service.field("conversationKey"))
            val legacy = android.content.Intent(started).replaceExtras(android.os.Bundle())
            service.onStartCommand(legacy, 0, 3)
            service.onStartCommand(legacy, 0, 4)
            assertEquals("keep this draft", input.value)
            assertEquals(key, service.field("conversationKey"))
        } finally { host.destroy() }
    }

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
