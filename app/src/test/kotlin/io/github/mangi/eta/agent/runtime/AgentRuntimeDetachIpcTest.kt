package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeDetachIpcTest {
    @Test fun repeatedAttachDetachStopsIpcDeliveryWithoutStoppingRuntime() {
        val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val service = host.get()
        val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }
            .get(service) as RuntimeRunRegistry
        val request = AgentRuntimeWire.RunRequest("detach-ipc", "task",
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList())
        val session = AgentRuntimeSession(request.runId)
        registry.admit(request) { RuntimeRunRegistry.Run(request, session) }
        val handler = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
            .getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }.newInstance(service) as Handler
        var delivered = 0
        try {
            repeat(10) {
                val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) { if (msg.what == AgentRuntimeWire.MSG_EVENT) delivered++ }
                })
                fun send(what: Int) = handler.handleMessage(Message.obtain(null, what).apply {
                    sendingUid = Process.myUid(); replyTo = receiver; data = AgentRuntimeWire.ackBundle(request.runId)
                })
                send(AgentRuntimeWire.MSG_ATTACH_RUN)
                shadowOf(Looper.getMainLooper()).idle()
                send(AgentRuntimeWire.MSG_DETACH_RUN)
            }
            session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, delivered)
            assertFalse(session.controller.isCancelled)
            assertSame(session, registry.get(request.runId)?.session)
        } finally {
            registry.finish(request.runId, session)
            host.destroy()
        }
    }
}
