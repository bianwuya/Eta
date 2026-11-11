package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.*
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeSteeringOffMainThreadTest {
    @Test fun slowSessionPersistenceDoesNotBlockMainAndAckWaitsForCommit() {
        val context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        val owner = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val service = owner.get()
        val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(service) as RuntimeRunRegistry
        val request = AgentRuntimeWire.RunRequest("slow-steering", "start",
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("slow-steering", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                AgentExternalArchivePayload("start", "conversation", "fixture").toJson()))
        val session = AgentRuntimeSession(request.runId)
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
        run.prepared(request)
        val lock = AgentRuntimeSession::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(session) as ReentrantLock
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = thread { lock.lock(); try { locked.countDown(); release.await(400, TimeUnit.MILLISECONDS) } finally { lock.unlock() } }
        val replies = mutableListOf<Boolean>()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) { override fun handleMessage(msg: Message) { replies += msg.data.getBoolean("accepted") } })
        val handler = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
            .getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }.newInstance(service) as Handler
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            handler.handleMessage(Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN).apply {
                sendingUid = Process.myUid(); replyTo = receiver
                data = AgentRuntimeWire.ackBundle(request.runId).apply { putString("queue_message_id", "q"); putString("steering_text", "guidance") }
            })
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("main ingest blocked for $elapsed ms", elapsed < 150)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("ACK must wait for the blocked durable path", replies.isEmpty())
            release.countDown(); worker.join(2_000)
            val deadline = System.nanoTime() + 5_000_000_000
            while (replies.isEmpty() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertEquals(listOf(true), replies)
            assertEquals("q", AgentRunCheckpointStore.list(context).single().events.filterIsInstance<AgentEvent.UserSupplementReceived>().single().queueMessageId)
        } finally { release.countDown(); worker.join(2_000); registry.finish(request.runId, session); owner.destroy(); EtaDatabase.closeForTests() }
    }
}
