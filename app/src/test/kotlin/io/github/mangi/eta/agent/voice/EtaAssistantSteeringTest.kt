package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.RuntimeRunRegistry
import io.github.mangi.eta.agent.runtime.RuntimeMessengerFixture
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.testing.WindowsAtomicFileShadow
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.AgentPendingQueueCodec
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [WindowsAtomicFileShadow::class])
class EtaAssistantSteeringTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun reopenedOverlayRestoresRejectedInputOnceAndKeepsOtherRunPending() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("reopened", "reopened-key")
            val run = fixture.admit(request)
            run.prepared(request)
            val id = AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "reopened-key")
            val owner = AgentPendingQueueCodec.voiceOwner(id)
            runBlocking {
                val dao = EtaDatabase.get(context).conversationDao()
                AgentPendingQueueCodec.write(dao, owner, AgentPendingQueueCodec.encode(context, listOf(
                    io.github.mangi.eta.ui.model.QueuedMessageUi("q", "retained", steeringRunId = run.runId),
                    io.github.mangi.eta.ui.model.QueuedMessageUi("other", "other run", steeringRunId = "other-run"))))
            }
            var restored = ""
            val job = fixture.worker { runBlocking {
                EtaAssistantSteeringCoordinator(fixture.context, fixture.client).reconcileUnconfirmed(run.runId, "reopened-key") { restored += it }
            } }
            fixture.result(job)
            assertEquals("retained", restored)
            val remaining = fixture.result(fixture.worker { runBlocking {
                AgentPendingQueueCodec.readOwned(EtaDatabase.get(context).conversationDao(), owner)
            } })
            assertEquals(listOf("other"), remaining.map { it.id })
            assertFalse(AgentRuntimeWire.MSG_STEER_RUN in fixture.messages)
        }
    }

    @Test fun failedPersistenceKeepsInputAndDoesNotSendToRuntime() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("save-fails", "failure-key")
            val run = fixture.admit(request); run.prepared(request)
            EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_voice_queue BEFORE INSERT ON agent_text_chunks WHEN NEW.owner_id LIKE 'voice-steering:%' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            var restored = ""
            var failure = false
            val work = fixture.worker { runBlocking {
                EtaAssistantSteeringCoordinator(fixture.context, fixture.client).steer(run.runId, "failure-key", "keep text",
                    onFailure = { failure = true }, restoreInput = { restored = it })
            } }
            fixture.result(work)
            assertTrue(failure)
            assertEquals("keep text", restored)
            assertFalse(AgentRuntimeWire.MSG_STEER_RUN in fixture.messages)
        }
    }

    @androidx.compose.runtime.Composable private fun testVoicePanelColors() = rememberEtaVoicePanelColors()

    @Before
    fun reset() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun close() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun composerInProcessingShowsSendWhenTextNotBlankAndStopWhenBlank() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val inputState = mutableStateOf("")
        val voiceState = mutableStateOf(EtaVoiceUiState(phase = EtaVoicePhase.PROCESSING))
        var submitCount = 0
        var stopCount = 0

        val view = ComposeView(activity)
        view.setParentCompositionContext(recomposer)
        view.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantComposer(
                    state = voiceState.value,
                    input = inputState.value,
                    speech = EtaSpeechState(),
                    recognitionEnabled = true,
                    speechLevel = { 0f },
                    inputFocusRequestKey = -1,
                    onInputFocus = {},
                    onMicrophone = {},
                    onCancelSpeech = {},
                    onFinishSpeech = {},
                    onOpenSpeechSettings = {},
                    onKeyboard = {},
                    keyboardVisible = false,
                    colors = testVoicePanelColors(),
                    onInputChange = { inputState.value = it },
                    onSubmit = { submitCount++ },
                    onStop = { stopCount++ },
                )
            }
        }
        activity.setContentView(view)

        fun advance() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            frameClock.sendFrame(System.nanoTime())
            val decor = activity.window.decorView
            decor.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1080, 2400)
        }

        fun findRoot(v: android.view.View): androidx.compose.ui.platform.ViewRootForTest? {
            if (v is androidx.compose.ui.platform.ViewRootForTest) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    findRoot(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        fun collectNodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::collectNodes)
        fun nodes(): List<SemanticsNode> {
            val root = findRoot(activity.window.decorView) ?: findRoot(view)
            return root?.semanticsOwner?.unmergedRootSemanticsNode?.let(::collectNodes).orEmpty()
        }

        try {
            advance()
            // When input is blank during PROCESSING: button should be Stop
            val stopDesc = activity.getString(R.string.action_stop)
            val stopButton = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(stopDesc) == true
            }
            assertNotNull("PROCESSING 且输入为空时应显示停止按钮", stopButton)
            var clickTarget = stopButton
            while (clickTarget != null && !clickTarget.config.contains(SemanticsActions.OnClick)) {
                clickTarget = clickTarget.parent
            }
            assertNotNull(clickTarget)
            clickTarget!!.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals("点击停止按钮应调用 onStop", 1, stopCount)
            assertEquals(0, submitCount)

            // When input has text during PROCESSING: button should be Send
            inputState.value = "新方向引导"
            advance()
            val sendDesc = activity.getString(R.string.voice_send)
            val sendButton = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(sendDesc) == true
            }
            assertNotNull("PROCESSING 且有文字时应显示发送按钮", sendButton)
            var sendClickTarget = sendButton
            while (sendClickTarget != null && !sendClickTarget.config.contains(SemanticsActions.OnClick)) {
                sendClickTarget = sendClickTarget.parent
            }
            assertNotNull(sendClickTarget)
            sendClickTarget!!.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals("点击发送按钮应调用 onSubmit", 1, submitCount)
            assertEquals("不应调用 onStop", 1, stopCount)
            val editor = nodes().first { it.config.contains(SemanticsActions.SetText) }
            assertFalse("生成中输入框必须可编辑", editor.config.contains(SemanticsProperties.Disabled))
            val ime = nodes().first { it.config.contains(SemanticsActions.OnImeAction) }
            assertTrue(ime.config[SemanticsActions.OnImeAction].action?.invoke() == true)
            assertEquals("IME 与点击必须共用提交路径", 2, submitCount)
        } finally {
            recomposer.cancel()
            scope.cancel()
            activity.finish()
        }
    }

    @Test
    fun overlayServiceSubmitsSteeringDuringActiveRunAndPersistsToRoom() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("overlay-steer", "voice-key")
            val run = fixture.admit(request)
            run.prepared(request)
            run.session.emit(AgentEvent.RoundStarted(1, 1))

            val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
            val service = host.get()

            service.javaClass.getDeclaredMethod("continueVoiceRun", AgentRuntimeWire.ActiveRunSnapshot::class.java)
                .apply { isAccessible = true }.invoke(service, run.snapshot())
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }

            val inputDelegate = service.javaClass.getDeclaredField("inputText\$delegate").apply { isAccessible = true }
                .get(service) as androidx.compose.runtime.MutableState<*>
            @Suppress("UNCHECKED_CAST")
            (inputDelegate as androidx.compose.runtime.MutableState<String>).value = "悬浮窗生成中引导"

            val submitInputMethod = service.javaClass.getDeclaredMethod("submitInput").apply { isAccessible = true }
            submitInputMethod.invoke(service)

            fixture.await { AgentRuntimeWire.MSG_STEER_RUN in fixture.messages }
            assertEquals(1, fixture.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })

            // Verify supplement is received and projected into overlay messages
            fixture.await {
                val uiStateField = service.javaClass.getDeclaredField("uiState\$delegate").apply { isAccessible = true }
                    .get(service) as androidx.compose.runtime.MutableState<*>
                val state = uiStateField.value as EtaVoiceUiState
                state.messages.any { it is UserMessageUi && it.content == "悬浮窗生成中引导" }
            }

            // Verify Room chunked pending queue has cleared the accepted item
            val conversationId = AgentConversationIdentity.archiveConversationId(
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "voice-key"
            )
            val dao = EtaDatabase.get(fixture.context).conversationDao()
            fixture.await {
                runBlocking { AgentPendingQueueCodec.read(dao, conversationId).isEmpty() }
            }
            runBlocking { assertTrue("已接收的引导应从 Room 队列中清除", AgentPendingQueueCodec.read(dao, conversationId).isEmpty()) }

            host.destroy()
        }
    }

    @Test
    fun overlayServiceRejectionPreservesInputTextAsEditable() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("overlay-reject", "voice-key-reject")
            val run = fixture.admit(request)
            run.prepared(request)
            // Mark preparing so steerRun returns Preparing
            RuntimeRunRegistry.Run::class.java.getDeclaredField("preparing").apply { isAccessible = true }.set(run, true)

            val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
            val service = host.get()

            service.javaClass.getDeclaredMethod("continueVoiceRun", AgentRuntimeWire.ActiveRunSnapshot::class.java)
                .apply { isAccessible = true }.invoke(service, run.snapshot())
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }

            val inputDelegate = service.javaClass.getDeclaredField("inputText\$delegate").apply { isAccessible = true }
                .get(service) as androidx.compose.runtime.MutableState<*>
            @Suppress("UNCHECKED_CAST")
            (inputDelegate as androidx.compose.runtime.MutableState<String>).value = "将被拒绝的引导"

            val submitInputMethod = service.javaClass.getDeclaredMethod("submitInput").apply { isAccessible = true }
            submitInputMethod.invoke(service)

            fixture.await { AgentRuntimeWire.MSG_STEER_RUN in fixture.messages }

            // Because runtime rejected (preparing), input should be restored as editable
            val deadline = System.nanoTime() + 5_000_000_000L
            while (inputDelegate.value != "将被拒绝的引导" && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("明确拒绝时应保留输入框文本供用户编辑", "将被拒绝的引导", inputDelegate.value)

            host.destroy()
        }
    }

    @Test
    fun overlayServiceTimeoutQueriesSteeringStatusWithoutResending() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("overlay-timeout", "voice-key-timeout")
            val run = fixture.admit(request)
            run.prepared(request)

            val host = Robolectric.buildService(EtaAssistantOverlayService::class.java).create()
            val service = host.get()

            service.javaClass.getDeclaredMethod("continueVoiceRun", AgentRuntimeWire.ActiveRunSnapshot::class.java)
                .apply { isAccessible = true }.invoke(service, run.snapshot())
            fixture.await { AgentRuntimeWire.MSG_ATTACH_RUN in fixture.messages }

            // Drop MSG_STEER_RUN reply to simulate timeout
            fixture.beforeDispatch = { msg ->
                if (msg.what == AgentRuntimeWire.MSG_STEER_RUN) msg.replyTo = null
                true
            }

            val inputDelegate = service.javaClass.getDeclaredField("inputText\$delegate").apply { isAccessible = true }
                .get(service) as androidx.compose.runtime.MutableState<*>
            @Suppress("UNCHECKED_CAST")
            (inputDelegate as androidx.compose.runtime.MutableState<String>).value = "超时待确认引导"

            val submitInputMethod = service.javaClass.getDeclaredMethod("submitInput").apply { isAccessible = true }
            submitInputMethod.invoke(service)

            // Wait for query steering to be sent
            fixture.await { AgentRuntimeWire.MSG_QUERY_STEERING in fixture.messages }
            assertEquals("超时后只查询状态，不自动重新发送 steerRun", 1, fixture.messages.count { it == AgentRuntimeWire.MSG_STEER_RUN })

            host.destroy()
        }
    }
}

