package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSteeringIpcTest {
    @Test
    fun voiceRunAcceptsGuidanceAtBoundaryWithoutReplacingIdentityOrCancelling() {
        val context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val service = serviceController.get()
        val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply {
            isAccessible = true
        }.get(service) as RuntimeRunRegistry
        val request = AgentRuntimeWire.RunRequest(
            runId = "queue-run", prompt = "开始", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            handoff = AgentRuntimeWire.EntryHandoff("queue-run", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, AgentExternalArchivePayload("开始", "voice-conversation", "Voice task").toJson()),
        )
        val session = AgentRuntimeSession(request.runId)
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
        run.prepared(request)
        val cancellations = AtomicInteger()
        session.controller.register { cancellations.incrementAndGet() }
        val replies = mutableListOf<Boolean>()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                assertEquals(AgentRuntimeWire.MSG_STEER_RUN_RESPONSE, msg.what)
                replies += msg.data.getBoolean("accepted")
            }
        })
        val handlerClass = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
        val handler = handlerClass.getDeclaredConstructor(AgentRuntimeService::class.java).apply {
            isAccessible = true
        }.newInstance(service) as Handler
        fun send(id: String, text: String) {
            handler.handleMessage(Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN).apply {
                sendingUid = Process.myUid()
                replyTo = receiver
                data = AgentRuntimeWire.ackBundle(request.runId).apply {
                    putString("queue_message_id", id)
                    putString("steering_text", text)
                }
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            send("queue-1", "新方向一")
            send("queue-1", "新方向一")
            send("queue-2", "新方向二")
            assertEquals(listOf(true, true, true), replies)
            assertSame(run, registry.get(request.runId))
            assertSame(session, run.session)
            assertEquals(request.runId, run.runId)
            assertEquals(AgentConversationIdentity.fromHandoff(request.handoff, request.runId), run.conversationId)
            assertEquals(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, run.snapshotRequest().handoff!!.source)
            val accepted = AgentRunCheckpointStore.list(context).single().events.filterIsInstance<AgentEvent.UserSupplementReceived>()
            assertEquals(listOf("queue-1", "queue-2"), accepted.map { it.queueMessageId })
            assertEquals(listOf("新方向一", "新方向二"), accepted.map { it.text })
            val replay = mutableListOf<AgentEvent>()
            session.attach(replay::add, {})
            assertEquals(accepted, replay.filterIsInstance<AgentEvent.UserSupplementReceived>())
            assertEquals(0, cancellations.get())
            assertFalse(session.controller.isCancelled)
            assertEquals("新方向一", session.controller.pollSteeringMessage())
            assertEquals("新方向二", session.controller.pollSteeringMessage())
            assertEquals(null, session.controller.pollSteeringOrSeal())
            send("queue-3", "已结束后保留队列")
            assertEquals(false, replies.last())
            assertEquals(0, cancellations.get())
        } finally {
            registry.finish(request.runId, session)
            serviceController.destroy()
            EtaDatabase.closeForTests()
            context.deleteDatabase("eta.db")
        }
    }
}
