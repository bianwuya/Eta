package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import io.github.mangi.eta.data.db.EtaDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunPresentationIpcTest {
    @Before fun resetDatabase() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }
    @After fun closeDatabase() { EtaDatabase.closeForTests() }

    @Test fun deferredPromptHeaderIsNeverReturnedAsThePreparedUserInput() {
        RuntimeMessengerFixture().use { f ->
            val request = f.request("deferred").copy(prompt = "full ".repeat(90_000), handoff = null)
            val bundle = AgentRuntimeWire.toBundle(request, emptyList(), f.context.cacheDir)
            AgentRuntimeWire.incomingRunRequestFromBundle(bundle).use { incoming ->
                assertTrue(incoming.hasDeferredPrompt)
                val run = f.admit(incoming.request)
                fun query() = (f.result(f.worker { f.client.queryRunPresentation(run.runId) })
                    as AgentRuntimeClient.RunPresentationQuery.Known).run
                assertEquals("", query().userText)
                run.prepared(incoming.materializeText())
                assertEquals(request.prompt, query().userText)
            }
        }
    }

    @Test fun preparingIsEmptyThenPreparedRefreshReturnsExactLongUserTextWithoutConfigOrOpaqueAdapter() {
        RuntimeMessengerFixture().use { f ->
            val text = "full user input ".repeat(45_000)
            val external = AgentExternalArchivePayload(text, "stable-key", "title",
                adapterPayload = JSONObject().put("api_key", "must-not-leave-adapter"))
            val original = f.request("presentation")
            val request = original.copy(prompt = text,
                handoff = original.handoff!!.copy(payload = external.toJson()),
                config = original.config.copy(apiKey = "must-not-leave-config", systemPrompt = "private-system"))
            val run = f.admit(request)
            fun query() = (f.result(f.worker { f.client.queryRunPresentation(run.runId) })
                as AgentRuntimeClient.RunPresentationQuery.Known).run
            val pending = query()
            assertEquals(AgentRuntimeWire.RunPhase.Preparing, pending.snapshot.phase)
            assertEquals("", pending.userText)
            run.prepared(request)
            val prepared = query()
            assertEquals(AgentRuntimeWire.RunPhase.Running, prepared.snapshot.phase)
            assertEquals(text, prepared.userText)
            assertEquals("stable-key", prepared.snapshot.conversationKey)
            val safePayload = requireNotNull(prepared.handoff).payload
            assertFalse(safePayload.contains("adapterPayload"))
            assertFalse(safePayload.contains("must-not-leave"))
            assertFalse(safePayload.contains("private-system"))
            assertEquals(0, f.messages.count { it == AgentRuntimeWire.MSG_START_RUN })
            assertFalse(run.session.controller.isCancelled)
        }
    }

    @Test fun completedAndArchivedQueriesAreExactReadOnlyAndDoNotAcknowledgeOrRestart() {
        RuntimeMessengerFixture().use { f ->
            val a = f.admit(f.request("ended-a"))
            a.prepared(a.request)
            f.complete(a, AgentRuntimeWire.RunResult(a.runId, true, "done"))
            f.admit(f.request("still-running-b"))
            val known = f.result(f.worker { f.client.queryRunPresentation("ended-a") })
                as AgentRuntimeClient.RunPresentationQuery.Known
            assertEquals("ended-a", known.run.snapshot.runId)
            assertEquals("fixture task", known.run.userText)
            assertEquals(AgentRuntimeClient.RunPresentationQuery.NotFound,
                f.result(f.worker { f.client.queryRunPresentation("missing") }))
            assertTrue(AgentRuntimeResultStore.list(f.context).any { it.result.runId == "ended-a" })
            val archived = AgentRunArchiveStore.list(f.context).single { it.result.runId == "ended-a" }
            AgentRunArchiveStore.remove(f.context, "ended-a")
            assertTrue(f.result(f.worker { f.client.queryRunPresentation("ended-a") }) is AgentRuntimeClient.RunPresentationQuery.Known)
            AgentRunArchiveStore.add(f.context, archived)
            AgentRuntimeResultStore.remove(f.context, "ended-a")
            assertTrue(f.result(f.worker { f.client.queryRunPresentation("ended-a") }) is AgentRuntimeClient.RunPresentationQuery.Known)
            assertFalse(AgentRuntimeWire.MSG_START_RUN in f.messages)
            assertFalse(AgentRuntimeWire.MSG_ACK_RESULT in f.messages)
        }

    @Test fun closedMailboxClosesLatePresentationDescriptors() {
        val context = RuntimeEnvironment.getApplication()
        val bundle = AgentRuntimeWire.runPresentationToBundle("late", AgentRuntimeWire.RunPresentation(
            AgentRuntimeWire.ActiveRunSnapshot("late", "late"), null, "text".repeat(90_000)), context.cacheDir)
        val descriptor = requireNotNull(bundle.getParcelable("presentation_user_text_eta_text_fd",
            android.os.ParcelFileDescriptor::class.java))
        AgentResultMailbox().also { it.close() }.set(bundle)
        assertFalse(descriptor.fileDescriptor.valid())
        assertFalse(AgentWireText.hasDescriptor(bundle, "presentation_user_text"))
    }
    }

    @Test fun preferenceIsExplicitAndDoesNotTouchGuiOwnerOrControllers() {
        RuntimeMessengerFixture().use { f ->
            val a = f.admit(f.request("a"))
            val b = f.admit(f.request("b"))
            val coordinator = AgentRuntimeService::class.java.getDeclaredField("guiControl")
                .apply { isAccessible = true }.get(f.service) as GuiControlCoordinator
            coordinator.openRun(a.runId, a.session.controller).withAction { }
            f.result(f.worker { f.client.queryRunPresentation(a.runId) })
            assertEquals("b", RuntimeNotificationSelection.select(f.registry.snapshots())!!.runId)
            f.result(f.worker { f.client.preferRun(a.runId) })
            f.await { RuntimeNotificationSelection.select(f.registry.snapshots())?.runId == "a" }
            assertEquals("a", coordinator.state.value.ownerRunId)
            assertFalse(a.session.controller.isCancelled)
            assertFalse(b.session.controller.isCancelled)
        }
    }

    @Test fun nonSameUidCannotQueryPresentationOrChangePreference() {
        RuntimeMessengerFixture().use { f ->
            val externalUid = Process.myUid() + 1000
            // Even an otherwise allowed assistant adapter cannot read this first-party projection.
            shadowOf(f.context.packageManager).setPackagesForUid(externalUid,
                arrayOf(io.github.mangi.eta.core.ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES.first()))
            val run = f.admit(f.request("private"))
            val before = run.snapshot()
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { error("unauthorized presentation response") }
            })
            val incoming = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
                .getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }
                .newInstance(f.service) as Handler
            listOf(AgentRuntimeWire.MSG_QUERY_RUN_PRESENTATION, AgentRuntimeWire.MSG_PREFER_RUN).forEach { what ->
                incoming.handleMessage(Message.obtain(null, what).apply {
                    sendingUid = externalUid
                    replyTo = receiver
                    data = AgentRuntimeWire.ackBundle(run.runId)
                })
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, run.snapshot())
        }
    }
}
