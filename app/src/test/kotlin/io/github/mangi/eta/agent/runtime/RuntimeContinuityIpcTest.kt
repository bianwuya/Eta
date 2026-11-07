package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import android.os.Message
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeContinuityIpcTest {
    @Before fun resetDatabase() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication<Application>().deleteDatabase("eta.db")
    }
    @After fun closeDatabase() { EtaDatabase.closeForTests() }

    @Test fun stopBeforeStartDeliveryIsOrderedAfterAdmissionAndDoesNotStartTwice() {
        RuntimeMessengerFixture().use { fixture ->
            val original = fixture.request("before-admission")
            val request = original.copy(handoff = original.handoff!!.copy(source = "fixture-entry"))
            val result = fixture.worker { fixture.client.run(request, stopRequested = { true }, onEvent = {}) }
            val stopped = fixture.result(result)
            assertFalse(stopped.ok)
            assertEquals("已停止", stopped.error)
            assertEquals(1, fixture.messages.count { it == AgentRuntimeWire.MSG_START_RUN })
            assertEquals(1, fixture.messages.count { it == AgentRuntimeWire.MSG_CANCEL })
            assertTrue(fixture.registry.snapshots().isEmpty())
        }
    }

    @Test fun actualStartIngestionRunsIndependentRequestsAndDuplicateStartOnlyAttaches() {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                entered.incrementAndGet()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val body = ("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n").toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            RuntimeMessengerFixture().use { fixture ->
                fun request(id: String): AgentRuntimeWire.RunRequest {
                    val request = fixture.request(id, "identity-$id")
                    return request.copy(config = request.config.copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1"),
                        handoff = request.handoff!!.copy(source = "fixture-entry"))
                }
                val requestA = request("real-a")
                val a = fixture.worker { fixture.client.run(requestA, onEvent = {}) }
                val b = fixture.worker { fixture.client.run(request("real-b"), onEvent = {}) }
                fixture.await { entered.get() == 2 }
                val duplicate = fixture.worker { fixture.client.run(requestA, onEvent = {}) }
                fixture.await { fixture.messages.count { it == AgentRuntimeWire.MSG_START_RUN } == 3 }
                assertEquals(2, fixture.registry.all().size)
                assertEquals(2, fixture.registry.all().map { it.conversationId }.distinct().size)
                fixture.result(fixture.worker { fixture.client.cancelRun(requestA.runId) })
                fixture.await { fixture.registry.get("real-a")?.session?.controller?.isCancelled != false }
                assertFalse(requireNotNull(fixture.registry.get("real-b")).session.controller.isCancelled)
                release.countDown()
                assertFalse(fixture.result(a).ok)
                assertFalse(fixture.result(duplicate).ok)
                val resultB = fixture.result(b)
                assertTrue(resultB.error, resultB.ok)
                assertEquals("answer", resultB.content)
                assertEquals(2, entered.get())
                assertTrue(fixture.registry.snapshots().isEmpty())
            }
        } finally { release.countDown(); server.stop(0); pool.shutdownNow() }
    }

    @Test fun messengerStopCannotOverrideCommitAlreadyInProgress() {
        RuntimeMessengerFixture().use { fixture ->
            val run = fixture.admit(fixture.request("commit-wins"))
            run.prepared(run.request)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val observer = fixture.worker { fixture.client.attachRun(run.runId, onEvent = {}) }
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }
            val commit = fixture.worker {
                run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "committed")) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                fixture.result(fixture.worker { fixture.client.cancelRun(run.runId) })
                assertFalse(run.session.controller.isCancelled)
                assertFalse(observer.isDone)
            } finally { release.countDown() }
            assertTrue(fixture.result(commit))
            assertTrue((fixture.result(observer) as AgentRuntimeClient.AttachOutcome.Completed).result.ok)
        }
    }

    @Test fun stopAfterIngestionKeepsSiblingAndCleanupLeaseUntilActualTerminal() {
        RuntimeMessengerFixture().use { fixture ->
            val a = fixture.admit(fixture.request("a", "identity-a"))
            val b = fixture.admit(fixture.request("b", "identity-b"))
            a.prepared(a.request)
            b.prepared(b.request)
            val observer = fixture.worker { fixture.client.attachRun(a.runId, onEvent = {}) }
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }
            fixture.result(fixture.worker { fixture.client.cancelRun(a.runId) })
            fixture.await { a.session.controller.isCancelled }
            assertFalse(a.session.isTerminal)
            assertFalse(b.session.controller.isCancelled)
            assertFalse(observer.isDone)
            assertNotEquals(a.conversationId, b.conversationId)
            fixture.complete(a, AgentRuntimeWire.RunResult(a.runId, true, "late success"))
            val outcome = fixture.result(observer) as AgentRuntimeClient.AttachOutcome.Completed
            assertFalse(outcome.result.ok)
            assertEquals("已停止", outcome.result.error)
            assertSame(b, fixture.registry.get(b.runId))
            fixture.complete(b, AgentRuntimeWire.RunResult(b.runId, true, "B finished"))
        }
    }

    @Test fun interruptedAttachUnsubscribesAndNewAttachReplaysThenReceivesLiveResult() {
        RuntimeMessengerFixture().use { fixture ->
            val run = fixture.admit(fixture.request("continue", "original-identity"))
            run.prepared(run.request)
            val first = mutableListOf<AgentEvent>()
            val thread = AtomicReference<Thread>()
            val old = fixture.worker {
                thread.set(Thread.currentThread())
                fixture.client.attachRun(run.runId, onEvent = first::add)
            }
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }
            thread.get().interrupt()
            assertEquals(AgentRuntimeClient.AttachOutcome.Unavailable, fixture.result(old))
            fixture.await { AgentRuntimeWire.MSG_DETACH_RUN in fixture.messages }
            val event = AgentEvent.RoundStarted(1, 1)
            run.session.emit(event)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(first.isEmpty())
            assertFalse(run.session.controller.isCancelled)
            val replay = mutableListOf<AgentEvent>()
            val live = mutableListOf<AgentEvent>()
            val next = fixture.worker {
                fixture.client.attachRun(run.runId, onReplay = { replay += it }, onEvent = live::add)
            }
            fixture.await { fixture.messages.count { it == AgentRuntimeWire.MSG_ATTACH_RUN } == 2 && replay.isNotEmpty() }
            val second = AgentEvent.RoundStarted(2, 2)
            run.session.emit(second)
            fixture.complete(run, AgentRuntimeWire.RunResult(run.runId, true, "done"))
            assertTrue(fixture.result(next) is AgentRuntimeClient.AttachOutcome.Completed)
            assertEquals(listOf(event), replay)
            assertEquals(listOf(second), live)
            assertEquals("original-identity", AgentExternalArchivePayload.from(run.request.handoff!!.payload)!!.conversationKey)
            assertFalse(run.session.controller.isCancelled)
        }
    }

    @Test fun fallbackAttachPreservesOutboxAndCanReadTheOwnedFullResult() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("fallback")
            val full = AgentRuntimeWire.RunResult(request.runId, true, "durable full result")
            AgentRuntimeResultStore.add(fixture.context, AgentRuntimeWire.CompletedRun(request.handoff!!, full, 1L))
            fixture.beforeDispatch = { msg ->
                if (msg.what == AgentRuntimeWire.MSG_ATTACH_RUN) {
                    msg.replyTo.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                        data = AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult(request.runId, false, "",
                            "transport fallback", contextSnapshotRef = request.runId))
                    })
                    false
                } else true
            }
            val outcome = fixture.result(fixture.worker { fixture.client.attachRun(request.runId, onEvent = {}) })
                as AgentRuntimeClient.AttachOutcome.Completed
            assertEquals(request.runId, outcome.result.contextSnapshotRef)
            assertFalse(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
            EtaDatabase.closeForTests()
            assertEquals(full, AgentRuntimeResultStore.list(fixture.context).single().result)
            val recovered = fixture.result(fixture.worker { fixture.client.queryCompletedRuns() })
                as AgentRuntimeClient.CompletedRunsQuery.Known
            assertEquals(full, recovered.runs.single().result)
            assertEquals(full, AgentRuntimeResultStore.readOwned(fixture.context, request.runId, request.handoff!!.payload)?.result)
            assertNull(AgentRuntimeResultStore.readOwned(fixture.context, request.runId, "different-owner"))
            assertFalse(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
        }
    }

    @Test fun capacityNeverReplacesIndependentVoiceIdentity() {
        RuntimeMessengerFixture().use { fixture ->
            val accepted = (0 until RuntimeRunRegistry.MAX_ACTIVE_RUNS).map { fixture.admit(fixture.request("capacity-$it")) }
            val before = fixture.registry.snapshots().map { it.runId }.toSet()
            val result = fixture.worker { fixture.client.run(fixture.request("overflow"), onEvent = {}) }
            assertFalse(fixture.result(result).ok)
            assertEquals(before, fixture.registry.snapshots().map { it.runId }.toSet())
            assertTrue(accepted.none { it.session.controller.isCancelled })
            assertEquals(accepted.size, accepted.map { it.conversationId }.distinct().size)
        }
    }

    @Test fun runtimeServiceRecreationReturnsDurableResultWithoutRestartingExecution() {
        val id = "service-recreation"
        RuntimeMessengerFixture().use { fixture ->
            val run = fixture.admit(fixture.request(id))
            run.prepared(run.request)
            fixture.complete(run, AgentRuntimeWire.RunResult(id, true, "saved before destruction"))
        }
        EtaDatabase.closeForTests()
        RuntimeMessengerFixture().use { fixture ->
            val outcome = fixture.result(fixture.worker { fixture.client.attachRun(id, onEvent = {}) })
            assertEquals(AgentRuntimeClient.AttachOutcome.NotActive, outcome)
            val completed = fixture.result(fixture.worker { fixture.client.queryCompletedRuns() })
                as AgentRuntimeClient.CompletedRunsQuery.Known
            val durable = completed.runs.single { it.result.runId == id }
            assertEquals("saved before destruction", durable.result.content)
            assertEquals(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, durable.handoff.source)
            assertEquals(id, AgentExternalArchivePayload.from(durable.handoff.payload)!!.conversationKey)
            assertTrue(fixture.registry.snapshots().isEmpty())
            assertFalse(AgentRuntimeWire.MSG_START_RUN in fixture.messages)
            assertFalse(AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val app = AgentAppState(fixture.context, scope)
                fixture.await {
                    app.conversationPaneState.tasksConnected &&
                        AgentRuntimeWire.MSG_ACK_RESULT in fixture.messages
                }
                val conversationId = AgentConversationIdentity.archiveConversationId(
                    AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, id,
                )
                val restored = requireNotNull(fixture.result(fixture.worker { runBlocking {
                    AgentConversationStore.loadConversation(fixture.context, conversationId)
                } }))
                assertEquals(1, restored.messages.filterIsInstance<UserMessageUi>().size)
                assertEquals(1, restored.messages.filterIsInstance<AgentMessageUi>()
                    .count { it.content == "saved before destruction" })
                assertTrue(AgentRuntimeResultStore.list(fixture.context).isEmpty())
                assertFalse(AgentRuntimeWire.MSG_START_RUN in fixture.messages)
            } finally {
                scope.cancel()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
}
