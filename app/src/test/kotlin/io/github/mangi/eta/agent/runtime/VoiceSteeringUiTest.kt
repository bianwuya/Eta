package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.AgentChatQueueStrip
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSteeringUiTest {
    @Before fun reset() { EtaDatabase.closeForTests(); RuntimeEnvironment.getApplication().deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun unconfirmedInputCannotBeRecalledDeletedOrRetriedUntilRuntimeReconcilesIt() {
        RuntimeMessengerFixture().use { f ->
            val run = f.admit(f.request("ui-unconfirmed")); run.prepared(run.request)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val app = AgentAppState(f.context, scope)
            try {
                val opened = scope.async { app.openRunConversation(run.runId) }
                f.await { opened.isCompleted && app.homeState.isStreaming && app.homeState.isConversationLoaded }
                f.beforeDispatch = { msg -> msg.what != AgentRuntimeWire.MSG_STEER_RUN && msg.what != AgentRuntimeWire.MSG_QUERY_STEERING }
                app.interruptAndSendCurrentMessage("unknown delivery")
                // Two bounded protocol timeouts (ACK and status), driven without sending twice.
                val deadline = System.nanoTime() + 25_000_000_000L
                while (app.homeState.queuedMessages.firstOrNull()?.steeringUnconfirmed != true && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
                }
                val queue = app.homeState.queuedMessages.single()
                assertTrue(queue.steeringUnconfirmed)
                app.recallQueuedMessage(queue.id, "")
                app.removeQueuedMessage(queue.id)
                app.steerQueuedMessage(queue.id)
                assertEquals(queue, app.homeState.queuedMessages.single())
                assertEquals(1, f.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })
                // Resume/recovery calls this same reconciliation method. It must clear the
                // unknown state only after this run explicitly confirms absence of the ID.
                f.await { ShadowToast.getTextOfLatestToast()?.contains("未确认") == true }
                f.beforeDispatch = { true }
                val method = AgentAppState::class.java.declaredMethods.single { it.name == "reconcileSteering" }
                    .apply { isAccessible = true }
                val finished = CompletableDeferred<Unit>()
                method.invoke(app, run.runId, object : kotlin.coroutines.Continuation<Unit> {
                    override val context = scope.coroutineContext
                    override fun resumeWith(result: Result<Unit>) { result.fold({ finished.complete(Unit) }, finished::completeExceptionally) }
                }).let { if (it != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) finished.complete(Unit) }
                f.await { finished.isCompleted && !app.homeState.queuedMessages.single().steeringUnconfirmed }
                assertFalse(app.homeState.queuedMessages.single().immediateInterrupt)
                assertEquals("unknown delivery", app.homeState.queuedMessages.single().content)
                assertEquals(1, f.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })
            } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    @Test fun actualGuidanceButtonKeepsRejectedInputAndBecomesAvailableAgain() {
        RuntimeMessengerFixture().use { f ->
            val request = f.request("ui-rejection")
            val run = f.admit(request); run.prepared(request)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val frames = BroadcastFrameClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
            val app = AgentAppState(f.context, scope)
            val recomposer = Recomposer(scope.coroutineContext)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val host = ComposeView(activity.get())
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    AgentChatQueueStrip(app.homeState.queuedMessages, app.homeState.isStreaming,
                        onSteer = app::steerQueuedMessage, onRecall = { app.recallQueuedMessage(it, "") },
                        onRemove = app::removeQueuedMessage)
                }
            }
            activity.get().setContentView(host)
            fun advance() {
                Snapshot.sendApplyNotifications()
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                val decor = activity.get().window.decorView
                decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 1080, 2400)
            }
            fun nodes(): List<SemanticsNode> {
                fun root(view: View): ViewRootForTest? {
                    if (view is ViewRootForTest) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
                    return null
                }
                fun collect(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::collect)
                return root(activity.get().window.decorView)?.let { collect(it.semanticsOwner.unmergedRootSemanticsNode) }.orEmpty()
            }
            fun button(): SemanticsNode? = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.get().getString(R.string.chat_queue_steer_now)) == true
            }
            try {
                val opened = scope.async { app.openRunConversation(run.runId) }
                f.await { opened.isCompleted && app.homeState.isStreaming && app.homeState.isConversationLoaded }
                app.sendCurrentMessage("retained guidance")
                repeat(10) { advance() }
                assertEquals("retained guidance", app.homeState.queuedMessages.single().content)
                RuntimeRunRegistry.Run::class.java.getDeclaredField("preparing").apply { isAccessible = true }.set(run, true)
                var target = requireNotNull(button())
                while (!target.config.contains(SemanticsActions.OnClick)) target = requireNotNull(target.parent)
                assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
                f.await { ShadowToast.getTextOfLatestToast()?.contains("仍在准备") == true }
                repeat(10) { advance() }
                val kept = app.homeState.queuedMessages.single()
                assertEquals("retained guidance", kept.content)
                assertFalse(kept.immediateInterrupt)
                assertFalse(kept.steeringUnconfirmed)
                assertNotNull(button())
                assertNull(run.session.controller.pollSteeringInput())
                assertEquals(1, f.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })
            } finally {
                host.disposeComposition(); recomposer.cancel(); scope.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                activity.pause().stop().destroy()
            }
        }
    }
}
