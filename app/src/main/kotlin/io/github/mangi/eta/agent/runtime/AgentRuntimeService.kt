package io.github.mangi.eta.agent.runtime

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.local.conversationcommunication.ConversationMessageEnvelope
import io.github.mangi.eta.local.conversationcommunication.ConversationMessageFileOutbox
import io.github.mangi.eta.local.conversationcommunication.ConversationOutboxWrite
import io.github.mangi.eta.local.conversationcommunication.ConversationOutcomeRecord
import io.github.mangi.eta.local.conversationcommunication.ConversationTerminalOutcome
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.local.guipresentation.GuiPresentationState
import io.github.mangi.eta.local.guipresentation.GuiPresentationDiagnostics
import io.github.mangi.eta.local.guipresentation.GuiVisibilityGate
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    // Accessed on main only. Binder identity permits explicit unsubscribe and dead-client cleanup.
    private val activeRunSubscribers = linkedMapOf<IBinder, Messenger>()
    private var lastPublishedRuns: List<AgentRuntimeWire.ActiveRunSnapshot>? = null
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()

    /** 跨会话消息与会话终态共用同一份原子写入记录；懒创建，避免服务启动即做文件 IO。 */
    private val conversationOutbox by lazy {
        ConversationMessageFileOutbox(File(filesDir, "conversation-communication/outbox.json"))
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    @Volatile private var lastGuiOwnerRunId: String? = null
    private var displayedRun: RuntimeRunRegistry.Run? = null
    private val presentation = GuiPresentationState()
    private val presentationRunId = mutableStateOf<String?>(null)
    private val capsuleOwnerKey = mutableStateOf("")
    private val visibilityGate = GuiVisibilityGate(
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        dispatch = { mainHandler.post(it) },
    )
    private val visibleWaiters = linkedMapOf<GuiVisibilityGate.Attempt, () -> Unit>()
    private var latestPresentationAttempt: GuiVisibilityGate.Attempt? = null
    private var capsulePaintedOwner: String? = null
    private var capsuleFramePending: Any? = null
    private var overlayEpoch = 0L
    private var overlayConnection: AgentAccessibilityService.Companion.Connection? = null
    @Volatile private var restoreAfterReconnect = false
    private var blockedPresentationRunId: String? = null
    private var operationPresentationRunId: String? = null
    private val taskPausedRuns = mutableSetOf<String>()
    @Volatile private var clearanceIdentity: Any = Any()
    private var releaseVisual: Runnable? = null
    private var diagnosticEntryPackage: String? = null

    // JVM lifecycle tests exercise real window management without starting an endless Compose frame clock.
    @androidx.annotation.VisibleForTesting
    internal var suppressOverlayCompositionForTest = false

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 窗口只投影当前手机控制者；各运行的状态保留在自己的记录中。 */
    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    private val capsuleExpanded = mutableStateOf(false)
    private val capsuleSupplementFocusable = mutableStateOf(false)
    /** Agent 手势穿过胶囊时为 true：窗口不可触摸，胶囊淡出让路。 */
    private val capsuleYielded = mutableStateOf(false)
    private var restoreCapsule: Runnable? = null
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            cancelRestore()
            val epoch = overlayEpoch
            val owner = displayedRun?.runId
            restoreCapsule = Runnable {
                if (!destroyed && epoch == overlayEpoch && owner == displayedRun?.runId) {
                    setCapsuleYielded(false)
                }
            }.also { mainHandler.postDelayed(it, delayMs) }
        }

        override fun cancelRestore() {
            restoreCapsule?.let(mainHandler::removeCallbacks)
            restoreCapsule = null
        }

        override fun presentationIdentity(): Any = clearanceIdentity
    }
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        serviceScope.launch { guiControl.state.collect(::applyGuiControlState) }
        serviceScope.launch { AgentAccessibilityService.connection.collect(::onAccessibilityConnectionChanged) }
        serviceScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                val wasActive = virtualDisplayState.value.active
                if (virtualDisplayState.value.materializingToMain != snapshot.materializingToMain) {
                    capsulePaintedOwner = null
                }
                virtualDisplayState.value = snapshot
                if (wasActive != snapshot.active) {
                    if (!snapshot.active) VirtualDisplayController.setStageExpanded(false)
                    updateCapsuleWindowBounds()
                    displayedRun?.let { syncActiveRunOverlayVisibility(it) }
                }
            }
        }
    }

    private fun onAccessibilityConnectionChanged(connection: AgentAccessibilityService.Companion.Connection) {
        if (destroyed || connection != AgentAccessibilityService.connection.value) return
        val bound = overlayConnection
        if (bound != null && bound != connection) {
            removeAmbientWindows()
            operationPresentationRunId = null
            if (displayedRun?.runId?.let { it !in taskPausedRuns } != false && !guiControl.state.value.userPaused) {
                state.value = state.value.copy(phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
            }
            // Old gate disposal is tied to its own connection, not a replacement window.
            restoreAfterReconnect = true
        }
        if (restoreAfterReconnect && connection.service != null) {
            restoreAfterReconnect = false
            operationPresentationRunId = null
            presentation.forgetVisible()
            val run = displayedRun
            if (run != null && runs.contains(run) && !run.session.isTerminal &&
                !run.session.controller.isCancelled) syncActiveRunOverlayVisibility(run, allowNeutralOwner = true)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || runs.all().isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runs.all().isNotEmpty()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        destroyed = true
        visibilityGate.close()
        visibleWaiters.clear()
        if (activeInstance === this) activeInstance = null
        setKeepScreenOn(false)
        runs.all().forEach { run ->
            run.releaseIncoming()
            run.session.cancel("Agent Runtime 服务已停止")
        }
        displayedRun = null
        serviceScope.cancel()
        AgentExecutionService.updateRunStatus(this, null, activeRunCount = 0)
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        VirtualDisplayController.release(this)
        unregisterResultCardBack()
        resultCardView?.let { removePresentationView(it, "result") }
        capsuleView?.let { removePresentationView(it, "capsule") }
        glowView?.let { removePresentationView(it, "glow") }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                } else if (msg.what == AgentRuntimeWire.MSG_STEER_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_STEER_RUN -> {
                    val data = msg.data ?: return
                    ingestSteering(data, msg.replyTo, msg.sendingUid == Process.myUid())
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> {
                    sendActiveRuns(msg.replyTo, msg.sendingUid == Process.myUid())
                }

                AgentRuntimeWire.MSG_SUBSCRIBE_ACTIVE_RUNS -> {
                    // The presentation contains user titles; external runtime entry packages cannot subscribe.
                    if (msg.sendingUid == Process.myUid()) {
                        msg.replyTo?.let { subscriber ->
                            activeRunSubscribers[subscriber.binder] = subscriber
                            sendActiveRunsChanged(subscriber, runs.snapshots())
                        }
                    }
                }

                AgentRuntimeWire.MSG_UNSUBSCRIBE_ACTIVE_RUNS -> {
                    if (msg.sendingUid == Process.myUid()) msg.replyTo?.let { activeRunSubscribers.remove(it.binder) }
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    /** Decode image FDs away from main; acknowledge only after actual enqueue, never on preparation alone. */
    private fun ingestSteering(data: Bundle, replyTo: Messenger?, sameUid: Boolean) {
        val runId = AgentRuntimeWire.runIdFromBundle(data)
        val queueId = data.getString("queue_message_id").orEmpty()
        fun acknowledge(accepted: Boolean, invalid: Boolean = false) {
            runCatching { replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN_RESPONSE).apply {
                this.data = AgentRuntimeWire.ackBundle(runId).apply {
                    putString("queue_message_id", queueId)
                    putBoolean("accepted", accepted)
                    putBoolean("invalid_input", invalid)
                }
            }) }
        }
        val active = runs.get(runId)
        if (!sameUid || queueId.isBlank() || active?.request?.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentRuntimeWire.closeImageDescriptors(data)
            acknowledge(false)
            return
        }
        val wireImages = try { AgentRuntimeWire.incomingImages(data) } catch (_: Exception) {
            acknowledge(false, true); return
        }
        fun accept(text: String, images: List<AgentModelClient.ModelImage>, previews: List<String>) {
            val event = if (!destroyed && runs.contains(active)) active.supplement(text, queueId, images, previews) else null
            if (event != null) {
                active.overlayState = active.overlayState.applyEvent(event)
                if (displayedRun?.runId == runId) state.value = active.overlayState
            }
            acknowledge(event != null)
        }
        if (wireImages.isEmpty()) {
            try { accept(AgentWireText.read(data, "steering_text").orEmpty().trim(), emptyList(), emptyList()) }
            catch (_: Exception) { acknowledge(false, true) }
            finally { AgentRuntimeWire.closeImageDescriptors(data) }
        } else {
            thread(name = "agent-runtime-steering-images") {
                val prepared = runCatching {
                    val text = AgentWireText.read(data, "steering_text").orEmpty().trim()
                    val images = AgentRuntimeImageTransfer.materializeImages(wireImages)
                    val previews = images.map { it.reference }
                    // History/recovery retains the original encoded input; UI samples its own thumbnail.
                    Triple(text, images, previews)
                }
                AgentRuntimeWire.closeImageDescriptors(data)
                mainHandler.post {
                    prepared.fold(onSuccess = { (text, images, previews) -> accept(text, images, previews) },
                        onFailure = { acknowledge(false, true) })
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val run = admitRun(incoming.request, replyTo, incoming) ?: return
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                run.session.controller.throwIfCancelled()
                run.awaitPredecessors()
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.currentRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                run.releaseIncoming()
                if (destroyed || !runs.contains(run) || run.session.controller.isCancelled) return@post
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        run.prepared(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                        )
                        publishExecutionState()
                        executePreparedRun(run)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        failBeforeExecution(
                            run,
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val run = admitRun(request, replyTo) ?: return
        run.prepared(request)
        executePreparedRun(run)
    }

    private fun admitRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger?,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
        onRejected: (String) -> Unit = {},
    ): RuntimeRunRegistry.Run? {
        if (destroyed) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(replyTo, AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务已停止"))
            onRejected("Agent Runtime 服务已停止")
            return null
        }
        val admission = runs.admit(request) {
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession(
                runId = request.runId,
                operation = request.operation,
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                beforeTerminalPublish = {
                    runs.finish(request.runId, session)?.releaseIncoming()
                    AgentExecutionService.release("run:${request.runId}")
                    mainHandler.post {
                        if (!destroyed) {
                            taskPausedRuns.remove(request.runId)
                            restorePausedTaskControl()
                            publishExecutionState()
                            updateKeepScreenOn()
                            if (runs.all().isEmpty() && displayedRun == null && !guiControl.state.value.userPaused) stopSelf()
                        }
                    }
                },
            )
            RuntimeRunRegistry.Run(request, session, incoming) {
                sendRequestIngestedTo(replyTo, request.runId)
            }
        }
        val run = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission.run
            is RuntimeRunRegistry.Admission.Duplicate -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                if (replyTo != null) {
                    val attached = admission.run.session.attach(
                        eventSink = { event -> sendEventTo(replyTo, event) },
                        resultSink = { result -> sendResultTo(replyTo, result) },
                    )
                    if (!attached) sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                        request.runId, false, "", "任务已结束，请恢复完整结果",
                        contextSnapshotRef = request.runId, operation = request.operation,
                    ))
                }
                return null
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                    request.runId, false, "", admission.message,
                    operation = request.operation, rewriteTargetMessageId = request.rewriteTargetMessageId,
                ))
                onRejected(admission.message)
                return null
            }
        }
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { mainHandler.post { cancelRun(request.runId) } }
        if (!executionHeld && !allowBoundFallback) {
            failBeforeExecution(run, "无法启动后台执行服务，请返回 Eta 后重试")
            onRejected("无法启动后台执行服务，请返回 Eta 后重试")
            return null
        }
        // 新 run 被接纳即视为用户/入口重新开始该会话，清除上一次停止/暂停的阻塞标记。
        clearConversationOutcome(AgentConversationIdentity.fromHandoff(request.handoff, request.runId))
        admission.replacedRuns.forEach { cancelRun(it.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        publishExecutionState()
        return run
    }

    private fun failBeforeExecution(run: RuntimeRunRegistry.Run, message: String) {
        run.releaseIncoming()
        val result = AgentRuntimeWire.RunResult(
            run.runId, false, "", message,
            operation = run.request.operation, rewriteTargetMessageId = run.request.rewriteTargetMessageId,
        )
        dispatchResultIo {
            run.session.complete(result) { committedResult ->
                runCatching { persistRunArtifacts(run.snapshotRequest(), committedResult, emptyList()) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.error("Agent runtime preparation result persistence failed: type=${throwable.safeLogType()}")
                    }
            }
        }
    }

    private fun showInitialTaskControl(run: RuntimeRunRegistry.Run) {
        // The voice/power entry had an on-screen task control before its first GUI action.
        // Do not turn unrelated background conversations into persistent overlays.
        if (runs.contains(run) && !run.session.isTerminal &&
            run.request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE &&
            guiControl.state.value.ownerRunId == null && displayedRun == null) {
            bindDisplayedRun(run)
            state.value = run.overlayState
            syncActiveRunOverlayVisibility(run)
        }
    }

    private fun executePreparedRun(run: RuntimeRunRegistry.Run) {
        if (!runs.contains(run)) return
        showInitialTaskControl(run)
        val screenOnBinding = run.session.controller.register { mainHandler.post(::updateKeepScreenOn) }
        thread(name = "agent-runtime") {
            try {
                executeRun(run)
            } finally {
                screenOnBinding.close()
            }
        }
    }

    private fun executeRun(run: RuntimeRunRegistry.Run) {
        val conversationMessaging = conversationMessagingFor(run)
        // 目标会话重新运行时补投此前排队的跨会话消息（只在完整 turn 边界交付）。
        conversationMessaging?.service?.deliverQueued(run.conversationId, run.runId)
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            guiControl = guiControl,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = run::snapshotRequest,
            onAcceptedEvent = { event, _ ->
                handleAcceptedRunEvent(run, event)
            },
            persistArtifacts = ::persistRunArtifacts,
            conversationMessaging = conversationMessaging,
            beforeGuiAction = { controller, isCurrent, entryPackage ->
                awaitGuiPresentation(run, controller, isCurrent, entryPackage)
            },
        ).execute(run.session, run.request)
        if (!run.session.controller.isCancelled) {
            recordConversationOutcome(
                conversationId = run.conversationId,
                outcome = if (outcome.result.ok) {
                    ConversationTerminalOutcome.COMPLETED
                } else {
                    ConversationTerminalOutcome.FAILED
                },
                runId = run.runId,
            )
        }
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            run = run,
            result = outcome.result,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    RuntimeRunRegistry.CompletedContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    /** 只有主会话 chat run 才注入跨会话工具；语音、归档与子代理路径保持原状。 */
    private fun conversationMessagingFor(run: RuntimeRunRegistry.Run): AgentRuntimeConversationMessaging? {
        val request = run.request
        if (request.operation != AgentRuntimeWire.OP_CHAT) return null
        if (request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return null
        val conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
        if (conversationId.isBlank()) return null
        return AgentRuntimeConversationMessaging(
            context = this,
            runs = runs,
            runId = run.runId,
            conversationId = conversationId,
            outbox = conversationOutbox,
            permissions = ::currentRuntimePermissions,
            startRun = { request, envelope -> startCrossConversationRun(request, envelope) },
            allowSend = request.origin != AgentRuntimeWire.RUN_ORIGIN_CONVERSATION_MESSAGE,
        )
    }

    /**
     * 为闲置会话按现有发送链路开启新 run；返回 null 表示已接纳，否则是真实拒绝原因。
     *
     * 跨会话正文不放进 user prompt：run 接纳后先排入控制器的运行时数据队列，
     * 由 AgentLoop 在首个完整 turn 边界以 assistant 角色注入，避免伪装成用户发言。
     */
    private fun startCrossConversationRun(
        request: AgentRuntimeWire.RunRequest,
        envelope: ConversationMessageEnvelope,
    ): String? {
        var reason = "目标会话当前无法启动运行（可能已有任务或达到并发上限）"
        val run = admitRun(
            request = request,
            replyTo = null,
            incoming = null,
            onRejected = { message -> reason = message },
        ) ?: return reason
        run.prepared(request)
        run.session.controller.enqueueConversationMessage(
            AgentRunController.ConversationInput(
                messageId = envelope.messageId,
                sourceConversationId = envelope.sourceConversationId,
                sourceTitle = envelope.sourceTitle,
                sourceRunId = envelope.sourceRunId,
                createdAt = envelope.createdAt,
                envelopeText = envelope.render(),
            ),
        )
        executePreparedRun(run)
        return null
    }

    private fun recordConversationOutcome(
        conversationId: String,
        outcome: ConversationTerminalOutcome,
        runId: String,
    ) {
        if (conversationId.isBlank() || conversationId.startsWith("run:")) return
        if (AgentConversationIdentity.isArchivedConversationId(conversationId)) return
        val record = ConversationOutcomeRecord(conversationId, outcome, runId, System.currentTimeMillis())
        dispatchResultIo {
            when (val written = conversationOutbox.recordOutcome(record)) {
                is ConversationOutboxWrite.Stored -> Unit
                is ConversationOutboxWrite.Rejected -> AndroidAgentLogger.warnThrottled("conversation_outcome_rejected") {
                    "Conversation outcome rejected: code=${written.code}"
                }
                is ConversationOutboxWrite.Failed -> AndroidAgentLogger.warnThrottled("conversation_outcome_failed") {
                    "Conversation outcome write failed: reason=${written.message.take(120)}"
                }
            }
        }
    }

    private fun clearConversationOutcome(conversationId: String) {
        if (conversationId.isBlank() || conversationId.startsWith("run:")) return
        if (AgentConversationIdentity.isArchivedConversationId(conversationId)) return
        dispatchResultIo {
            when (val written = conversationOutbox.clearOutcome(conversationId)) {
                is ConversationOutboxWrite.Stored -> Unit
                is ConversationOutboxWrite.Rejected -> Unit
                is ConversationOutboxWrite.Failed -> AndroidAgentLogger.warnThrottled("conversation_outcome_clear_failed") {
                    "Conversation outcome clear failed: reason=${written.message.take(120)}"
                }
            }
        }
    }

    private fun handleAcceptedRunEvent(
        run: RuntimeRunRegistry.Run,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.GuiControlChanged) {
            run.guiStatus = event.status
            if (event.status == GuiControlStatus.Acquired) lastGuiOwnerRunId = run.runId
        }
        mainHandler.post {
            if (destroyed) return@post
            val guiState = guiControl.state.value
            val ownsGui = guiState.ownerRunId == run.runId
            // StateFlow 可以合并短暂的获得与释放；终态归属仍以顺序事件记录的最后所有者为准。
            if (event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired &&
                !guiState.userPaused && ownsGui && !run.session.isTerminal &&
                !run.session.controller.isCancelled) {
                bindDisplayedRun(run)
            }
            if (AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true)) {
                run.hasExecutedForegroundTool = true
            }
            runCatching {
                run.overlayState = run.overlayState.applyEvent(event)
                if (displayedRun === run && (ownsGui || guiState.ownerRunId == null) &&
                    !run.session.isTerminal && !run.session.controller.isCancelled) {
                    if (run.runId !in taskPausedRuns && !guiState.userPaused) {
                        state.value = if (ownsGui && operationPresentationRunId == run.runId &&
                            (latestPresentationAttempt?.canPresent() == true || latestPresentationAttempt?.allowed == true)) run.overlayState else run.overlayState.copy(
                            status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                    }
                    syncActiveRunOverlayVisibility(run)
                }
                publishExecutionState()
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        run: RuntimeRunRegistry.Run,
        result: AgentRuntimeWire.RunResult,
        completedContext: RuntimeRunRegistry.CompletedContext? = null,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            run.completedContext = completedContext
            val guiState = guiControl.state.value
            if ((displayedRun != null && displayedRun !== run) ||
                (lastGuiOwnerRunId != run.runId && displayedRun !== run) || guiState.userPaused ||
                (guiState.ownerRunId != null && guiState.ownerRunId != run.runId)) return@post
            bindDisplayedRun(run)
            updateKeepScreenOn()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.UserSupplementReceived) {
            dispatchResultIo {
                val message = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
                message.data = AgentRuntimeWire.eventToBundle(event, cacheDir)
                AgentWireText.send(target, message)
            }
            return
        }
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8),
                cacheDir,
            )
            AgentWireText.send(replyTo, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            val snapshots = runs.snapshots()
            val runId = snapshots.firstOrNull { it.runId == guiControl.state.value.ownerRunId }?.runId
                ?: snapshots.firstOrNull()?.runId.orEmpty()
            msg.data = AgentRuntimeWire.ackBundle(runId)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRunsChanged(subscriber: Messenger, snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>): Boolean =
        runCatching {
            subscriber.send(Message.obtain(null, AgentRuntimeWire.MSG_ACTIVE_RUNS_CHANGED).apply {
                data = AgentRuntimeWire.activeRunsToBundle(snapshots)
            })
        }.isSuccess

    private fun publishActiveRuns(snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>) {
        if (lastPublishedRuns == snapshots) return
        lastPublishedRuns = snapshots
        val iterator = activeRunSubscribers.values.iterator()
        while (iterator.hasNext()) if (!sendActiveRunsChanged(iterator.next(), snapshots)) iterator.remove()
    }

    private fun sendActiveRuns(replyTo: Messenger?, includePresentation: Boolean) {
        runCatching {
            replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                val snapshots = runs.snapshots()
                data = AgentRuntimeWire.activeRunsToBundle(if (includePresentation) snapshots else snapshots.map {
                    it.copy(title = "", conversationKey = "", createdAt = 0L, guiStatus = null)
                })
            })
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = runs.get(runId)?.session
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
    }

    private fun requestStop(runId: String?) {
        if (runId == null || runs.get(runId) == null) {
            if (!guiControl.state.value.userPaused && displayedRun?.runId == runId &&
                guiControl.state.value.ownerRunId == null) dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val run = runs.get(runId) ?: return
        if (run.preparing) {
            if (!run.session.requestStop()) return
            failBeforeExecution(run, "已停止")
            recordStopIfLastActive(run)
            return
        }
        val session = run.session
        if (session.requestStop()) {
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.Stopping)
            if (displayedRun === run && !guiControl.state.value.userPaused) state.value = run.overlayState
            updateKeepScreenOn()
            publishExecutionState()
            recordStopIfLastActive(run)
        }
    }

    /**
     * 用户/入口停止该会话后记录 STOPPED：跨会话消息只会排队，不会在重启或下次发送时擅自唤醒。
     * 允许替换时（同一会话的新 run 已接纳）不记录，避免把正常替换误判成用户停止。
     */
    private fun recordStopIfLastActive(run: RuntimeRunRegistry.Run) {
        val others = runs.all().any {
            it !== run && it.conversationId == run.conversationId && !it.session.isTerminal
        }
        if (others) return
        recordConversationOutcome(run.conversationId, ConversationTerminalOutcome.STOPPED, run.runId)
    }

    private fun requestPause(runId: String?) {
        val run = runId?.let(runs::get)?.takeIf { displayedRun === it && !it.session.isTerminal } ?: return
        val owner = guiControl.state.value.ownerRunId
        if (owner != null && owner != run.runId) return
        if (guiControl.state.value.userPaused) return
        val paused = if (owner == run.runId) guiControl.pauseForUser(run.runId) else {
            // Only this task is paused; never pause or acquire another task's GUI lock.
            run.session.controller.pause()
            taskPausedRuns.add(run.runId)
            state.value = run.overlayState.copy(phase = AgentOverlayPhase.PAUSED,
                status = AgentOverlayStatus.Paused, detailText = "任务已暂停")
            true
        }
        if (paused) recordConversationOutcome(run.conversationId, ConversationTerminalOutcome.PAUSED, run.runId)
    }

    private fun requestResume() = requestResumeFor(displayedRun?.runId)

    private fun requestResumeFor(runId: String?) {
        val run = displayedRun?.takeIf { it.runId == runId } ?: return
        if (run.runId in taskPausedRuns) {
            val currentOwner = guiControl.state.value.ownerRunId
            if (currentOwner != null && currentOwner != run.runId) return
            taskPausedRuns.remove(run.runId)
            if (runs.contains(run) && !run.session.isTerminal) {
                run.session.controller.resume()
                state.value = run.overlayState.copy(phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                restorePausedTaskControl()
            }
        } else if (guiControl.state.value.userPaused &&
            (guiControl.state.value.ownerRunId == run.runId || guiControl.state.value.ownerRunId == null)) {
            guiControl.resumeForUser()
        }
    }

    private fun requestSupplement(runId: String?, text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val run = displayedRun?.takeIf { it.runId == runId } ?: return
        if (guiControl.state.value.userPaused && guiControl.state.value.ownerRunId == null) return
        setCapsuleInputMode(focusable = false)
        runs.get(run.runId)?.let { active ->
            val event = active.supplement(supplementText)
            if (event == null) {
                if (!active.session.isTerminal) {
                    state.value = state.value.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                active.overlayState = active.overlayState.applyEvent(event)
                state.value = active.overlayState
                return
            }
        }

        val completed = run.completedContext ?: return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun ensureOverlayVisible() {
        val result = showOverlay()
        if (result is ToolExecutionDecision.Reject && AgentAccessibilityService.current() == null &&
            displayedRun?.let { runs.contains(it) && !it.session.isTerminal && !it.session.controller.isCancelled } == true) {
            // A real connection event, not a timer, retries this eligible task control.
            restoreAfterReconnect = true
        }
    }

    private fun awaitGuiPresentation(
        run: RuntimeRunRegistry.Run,
        controller: AgentRunController,
        isActionCurrent: () -> Boolean,
        entryPackage: String?,
    ): ToolExecutionDecision {
        val attemptConnection = java.util.concurrent.atomic.AtomicReference<AgentAccessibilityService.Companion.Connection?>(null)
        return visibilityGate.await(
        controller = controller,
        isCurrent = {
            !destroyed && runs.contains(run) && !run.session.isTerminal &&
                !run.session.controller.isPaused && !restoreAfterReconnect &&
                (attemptConnection.get() == null || attemptConnection.get() == AgentAccessibilityService.connection.value) &&
                guiControl.state.value.ownerRunId == run.runId &&
                !guiControl.state.value.userPaused && isActionCurrent()
        },
        present = { attempt ->
            if (attempt.canPresent()) {
                applyGuiControlState(guiControl.state.value, reveal = false)
                diagnosticEntryPackage = entryPackage
                blockedPresentationRunId = null
                latestPresentationAttempt = attempt
                attemptConnection.set(AgentAccessibilityService.connection.value)
                operationPresentationRunId = run.runId
                when (val shown = showOverlay(attempt::canPresent)) {
                    is ToolExecutionDecision.Reject -> {
                        tracePresentation("gate_rejected", run)
                        attempt.reject(shown)
                        if (AgentAccessibilityService.current() == null) {
                            restoreAfterReconnect = true
                            operationPresentationRunId = null
                            state.value = state.value.copy(phase = AgentOverlayPhase.RUNNING,
                                status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                        }
                        if (latestPresentationAttempt === attempt && displayedRun === run &&
                            !guiControl.state.value.userPaused && !restoreAfterReconnect &&
                            attemptConnection.get() == AgentAccessibilityService.connection.value) removeAmbientWindows()
                    }
                    ToolExecutionDecision.Allow -> {
                        attemptConnection.set(overlayConnection)
                        if (!attempt.canPresent()) {
                            if (latestPresentationAttempt === attempt && displayedRun === run &&
                                attemptConnection.get() == AgentAccessibilityService.connection.value &&
                                overlayConnection == attemptConnection.get()) removeAmbientWindows()
                        } else {
                            // The callback is driven by the existing capsule's settled Compose draw,
                            // not just attachment/pre-draw or an Activity lifecycle flag.
                            visibleWaiters[attempt] = {
                                if (!attempt.canPresent()) {
                                    visibleWaiters.remove(attempt)
                                } else if (displayedRun === run && capsuleIsVisible() &&
                                    ((capsulePaintedOwner == run.runId &&
                                        !virtualDisplayState.value.materializingToMain && !capsuleYielded.value) ||
                                        (presentation.hasControlledPresentation(run.runId, virtualDisplayState.value.materializingToMain) &&
                                            (!virtualDisplayState.value.materializingToMain || glowView?.isAttachedToWindow == true)))) {
                                    tracePresentation(if (capsuleYielded.value || virtualDisplayState.value.materializingToMain)
                                        "gate_controlled_path" else "gate_visible", run)
                                    attempt.complete(ToolExecutionDecision.Allow)
                                    visibleWaiters.remove(attempt)
                                }
                            }
                            if (attempt.canPresent()) {
                                state.value = run.overlayState
                                visibleWaiters[attempt]?.invoke()
                                capsuleView?.invalidate()
                            }
                        }
                    }
                }
            }
        },
        disposed = { attempt ->
            visibleWaiters.remove(attempt)
            if (!attempt.allowed && latestPresentationAttempt === attempt && displayedRun === run && visibleWaiters.isEmpty() &&
                !guiControl.state.value.userPaused) {
                tracePresentation("gate_disposed_rejected", run)
                val connection = attemptConnection.get()
                // A stale connection's disposal must not change restoration, block, or new view.
                if (!restoreAfterReconnect && connection != null &&
                    connection == AgentAccessibilityService.connection.value &&
                    (overlayConnection == null || overlayConnection == connection)) {
                    blockedPresentationRunId = run.runId
                    operationPresentationRunId = null
                    state.value = state.value.copy(phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                    removeAmbientWindows()
                }
            }
        },
    )
    }

    private fun capsuleIsVisible(): Boolean {
        val view = capsuleView ?: return false
        if (overlayConnection != AgentAccessibilityService.connection.value) return false
        return view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
            view.alpha > 0f && view.width > 0 && view.height > 0 &&
            view.getGlobalVisibleRect(Rect())
    }

    private fun onCapsuleVisibleFrame(epoch: Long, owner: String?) {
        if (destroyed || epoch != overlayEpoch || owner != presentationRunId.value ||
            !capsuleIsVisible() || capsuleYielded.value || virtualDisplayState.value.materializingToMain) return
        // A new owner must paint its own content before its action may proceed.
        val previousOwner = capsulePaintedOwner
        val contentIdentity = clearanceIdentity
        capsulePaintedOwner = owner
        if (operationPresentationRunId == owner &&
            (latestPresentationAttempt?.canPresent() == true || latestPresentationAttempt?.allowed == true)) {
            presentation.confirmVisible(owner)
        }
        if (capsuleFramePending === contentIdentity || (visibleWaiters.isEmpty() && previousOwner == owner)) return
        capsuleFramePending = contentIdentity
        mainHandler.post {
            if (capsuleFramePending === contentIdentity) capsuleFramePending = null
            if (!destroyed && epoch == overlayEpoch && overlayConnection == AgentAccessibilityService.connection.value &&
                owner == presentationRunId.value && contentIdentity == clearanceIdentity) {
                if (updateCapsuleTouchability()) visibleWaiters.values.toList().forEach { it() }
                else removeAmbientWindows()
            }
        }
    }

    private fun tracePresentation(event: String, run: RuntimeRunRegistry.Run? = displayedRun) {
        if (!GuiPresentationDiagnostics.enabled) return
        GuiPresentationDiagnostics.record(
            event = event,
            runId = run?.runId,
            source = run?.request?.handoff?.source,
            entryVisible = runCatching {
                diagnosticEntryPackage?.let { name ->
                    AgentAccessibilityService.current()?.packageWindowVisibility(name)?.name
                }
            }.getOrNull() ?: "unknown",
            overlayAttached = capsuleView?.isAttachedToWindow == true,
            owner = guiControl.state.value.ownerRunId,
        )
    }

    private fun syncActiveRunOverlayVisibility(run: RuntimeRunRegistry.Run?, allowNeutralOwner: Boolean = false) {
        if (run == null || ((!runs.contains(run) || run.session.isTerminal || run.session.controller.isCancelled) &&
                !guiControl.state.value.userPaused)) return
        if (restoreAfterReconnect && AgentAccessibilityService.current() == null) return
        val owner = guiControl.state.value.ownerRunId
        if (owner != null && owner != run.runId) return
        // Ownership notifications do not create an operation presentation after the gate deadline.
        // A reconnect may restore a neutral task control, never the rejected action.
        if (owner == run.runId && !guiControl.state.value.userPaused && !allowNeutralOwner &&
            (operationPresentationRunId != run.runId ||
                (latestPresentationAttempt?.canPresent() != true && latestPresentationAttempt?.allowed != true))) return
        if ((owner == null || allowNeutralOwner) && !guiControl.state.value.userPaused &&
            AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
                handoffSource = run.request.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
                virtualDisplayActive = virtualDisplayState.value.active,
            )) removeAmbientWindows() else ensureOverlayVisible()
    }

    private fun handleEtaUiVisibilityChanged() {
        if (destroyed) return
        val run = displayedRun ?: return
        if (runs.contains(run) && !run.session.isTerminal) {
            syncActiveRunOverlayVisibility(run)
            return
        }
        val suppress = AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
            handoffSource = run.request.handoff?.source,
            etaUiInForeground = MainActivity.isInForeground,
            virtualDisplayActive = virtualDisplayState.value.active,
        )
        if (resultCardView != null) {
            if (suppress) dismissResult(run)
            return
        }
    }

    private fun updateKeepScreenOn() {
        val guiState = guiControl.state.value
        val session = guiState.ownerRunId?.let(runs::get)?.session
        setKeepScreenOn(
            !guiState.userPaused && session != null && !session.isTerminal &&
                !session.controller.isCancelled && !session.controller.isPaused,
        )
    }

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State) =
        applyGuiControlState(guiState, reveal = true)

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State, reveal: Boolean) {
        if (destroyed || guiState != guiControl.state.value) return
        releaseVisual?.let(mainHandler::removeCallbacks)
        releaseVisual = null
        if (guiState.ownerRunId == null && !guiState.userPaused) restorePausedTaskControl()
        val owner = guiState.ownerRunId?.let(runs::get)?.takeIf {
            guiState.userPaused || (!it.session.isTerminal && !it.session.controller.isCancelled)
        }
        val wasReleasing = presentation.phase == GuiPresentationState.Phase.RELEASING
        presentation.update(owner?.runId, guiState.userPaused, SystemClock.uptimeMillis())
        if (owner != null && displayedRun !== owner) {
            operationPresentationRunId = null
            bindDisplayedRun(owner)
            AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
        }
        when {
            guiState.userPaused -> {
                state.value = (owner?.overlayState ?: AgentOverlayState.Initial).copy(
                    phase = AgentOverlayPhase.PAUSED,
                    status = AgentOverlayStatus.Paused,
                    detailText = "手机操作已暂停",
                )
                owner?.overlayState = state.value
                if (reveal) syncActiveRunOverlayVisibility(owner ?: displayedRun)
            }
            owner != null -> {
                if (wasReleasing) {
                    clearanceHost.cancelRestore()
                    capsuleYielded.value = false
                    presentation.setYielded(false)
                    capsulePaintedOwner = null
                }
                if (owner.overlayState.phase == AgentOverlayPhase.PAUSED && owner.runId !in taskPausedRuns) {
                    owner.overlayState = owner.overlayState.copy(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Continuing,
                        detailText = "",
                    )
                }
                state.value = if (owner.runId in taskPausedRuns) owner.overlayState.copy(
                    phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused, detailText = "任务已暂停")
                    else if (operationPresentationRunId == owner.runId &&
                        (latestPresentationAttempt?.canPresent() == true || latestPresentationAttempt?.allowed == true)) owner.overlayState
                    else owner.overlayState.copy(status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                capsuleOwnerKey.value = owner.runId
                if (reveal) syncActiveRunOverlayVisibility(owner)
            }
            else -> {
                operationPresentationRunId = null
                clearanceHost.cancelRestore()
                clearanceIdentity = Any()
                capsulePaintedOwner = null
                capsuleOwnerKey.value = displayedRun?.runId.orEmpty() + ":released"
                if (displayedRun?.runId?.let { it !in taskPausedRuns } != false) {
                    state.value = state.value.copy(phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Reasoning, thought = "", detailText = "")
                }
                setCapsuleInputMode(false)
                val run = displayedRun
                if (run != null && runs.contains(run) && !run.session.isTerminal && !run.session.controller.isCancelled) {
                    if (reveal) syncActiveRunOverlayVisibility(run)
                } else removeAmbientWindows()
            }
        }
        updateCapsuleTouchability()
        tracePresentation("owner_projection")
        updateKeepScreenOn()
        publishExecutionState()
    }

    private fun restorePausedTaskControl() {
        if (guiControl.state.value.ownerRunId != null || guiControl.state.value.userPaused) return
        val paused = taskPausedRuns.asSequence().mapNotNull(runs::get).firstOrNull {
            !it.session.isTerminal && !it.session.controller.isCancelled && it.session.controller.isPaused
        } ?: return
        if (displayedRun !== paused) bindDisplayedRun(paused)
        state.value = paused.overlayState.copy(phase = AgentOverlayPhase.PAUSED,
            status = AgentOverlayStatus.Paused, detailText = "任务已暂停")
        syncActiveRunOverlayVisibility(paused)
    }

    private fun bindDisplayedRun(run: RuntimeRunRegistry.Run) {
        if (displayedRun === run) return
        blockedPresentationRunId = null
        // A task paused without GUI ownership keeps its own controller paused across handoff.
        // Its resume control is restored only when that run is displayed again.
        // Keep the ambient windows attached during a handoff; only their owner/content changes.
        mainHandler.removeCallbacksAndMessages(hideToken)
        clearanceHost.cancelRestore()
        unregisterResultCardBack()
        resultCardView?.let { removePresentationView(it, "result") }
        resultCardView = null
        resultCardParams = null
        capsulePaintedOwner = null
        clearanceIdentity = Any()
        capsuleYielded.value = false
        presentation.setYielded(false)
        presentation.forgetVisible()
        displayedRun?.completedContext = null
        displayedRun = run
        diagnosticEntryPackage = null
        presentationRunId.value = run.runId
        capsuleOwnerKey.value = run.runId
        if (run.runId in taskPausedRuns) state.value = run.overlayState.copy(
            phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused, detailText = "任务已暂停")
        capsuleExpanded.value = false
        setCapsuleInputMode(false)
        if (capsuleView != null && !updateCapsuleTouchability()) removeAmbientWindows()
    }

    private fun publishExecutionState() {
        val guiState = guiControl.state.value
        val activeRuns = runs.all().filterNot { it.session.isTerminal }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            publishActiveRuns(activeRuns.map { it.snapshot() })
        } else {
            mainHandler.post { if (!destroyed) publishActiveRuns(runs.snapshots()) }
        }
        val owner = activeRuns.firstOrNull { it.runId == guiState.ownerRunId }
        val status = if (owner != null && guiState.userPaused) {
            AgentOverlayStatus.Paused
        } else {
            (owner ?: activeRuns.firstOrNull())?.overlayState?.status
        }
        AgentExecutionService.updateRunStatus(
            this,
            status,
            activeRunCount = activeRuns.size,
        )
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        capsuleView?.keepScreenOn = enabled
    }

    private fun showOverlay(): ToolExecutionDecision = showOverlay { !destroyed }

    private fun showOverlay(isCurrent: () -> Boolean): ToolExecutionDecision {
        fun stale() = ToolExecutionDecision.Reject("GUI_PRESENTATION_STALE", "手机操作权已变化，本次未执行")
        if (!isCurrent()) return stale()
        val connection = AgentAccessibilityService.connection.value
        val context: Context = connection.service ?: this
        val type = if (connection.service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (connection.service == null && !Settings.canDrawOverlays(this)) {
            tracePresentation("overlay_permission_missing")
            return ToolExecutionDecision.Reject("GUI_OVERLAY_PERMISSION_REQUIRED", "操作提示需要无障碍浮层或悬浮窗权限，本次未执行")
        }
        if (capsuleView?.isAttachedToWindow == true && overlayConnection == connection) return ToolExecutionDecision.Allow
        if (capsuleView != null || glowView != null) removeAmbientWindows()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ToolExecutionDecision.Reject("GUI_PRESENTATION_UNAVAILABLE", "操作提示窗口服务不可用，本次未执行")
        windowManager = wm
        overlayConnection = connection
        fun connectionCurrent() = AgentAccessibilityService.connection.value == connection && isCurrent()
        val epoch = ++overlayEpoch
        fun discardStale() {
            if (epoch != overlayEpoch) return
            val changed = AgentAccessibilityService.connection.value != connection
            removeAmbientWindows()
            if (changed && !destroyed) {
                restoreAfterReconnect = true
                mainHandler.post { onAccessibilityConnectionChanged(AgentAccessibilityService.connection.value) }
            }
        }

        // ── 氛围光窗口：全屏触摸穿透，彩虹光圈（后台虚拟屏运行时隐藏流光，仅承载全屏破壁动画） ─
        val glow = createOverlayComposeView(context) {
            AgentOverlayGlow(
                state = state.value,
                corners = screenCornerRadii(),
                virtualDisplay = virtualDisplayState.value,
            )
        }
        val glowLp = glowLayoutParams(type)
        if (!connectionCurrent()) { discardStale(); return stale() }
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            tracePresentation("glow_add_failed")
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
            if (glow.isAttachedToWindow) runCatching { wm.removeViewImmediate(glow) }
            if (AgentAccessibilityService.connection.value != connection) {
                discardStale()
                return stale()
            }
            if (epoch == overlayEpoch) removeAmbientWindows()
            return ToolExecutionDecision.Reject("GUI_PRESENTATION_FAILED", "操作提示流光窗口建立失败，本次未执行")
        }
        if (epoch != overlayEpoch) {
            runCatching { wm.removeViewImmediate(glow) }
            return stale()
        }
        glowView = glow
        glowParams = glowLp
        tracePresentation("glow_add_ok")

        // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态（含微缩镜），点击展开控制与画廊视窗 ─
        val capsule = createOverlayComposeView(context) {
            val controlRunId = presentationRunId.value
            val contentKey = capsuleOwnerKey.value
            val contentIdentity = clearanceIdentity
            fun isCurrentContent() = epoch == overlayEpoch && overlayConnection == connection &&
                AgentAccessibilityService.connection.value == connection && contentKey == capsuleOwnerKey.value &&
                controlRunId == presentationRunId.value && contentIdentity == clearanceIdentity
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = { if (isCurrentContent()) toggleCapsule() },
                onCollapsedSettled = { if (isCurrentContent()) onCapsuleCollapsedSettled() },
                onPause = {
                    if (!isCurrentContent()) return@AgentOverlayCapsule
                    VirtualDisplayController.setUserControlling(false)
                    requestPause(controlRunId)
                },
                onResume = {
                    if (!isCurrentContent()) return@AgentOverlayCapsule
                    VirtualDisplayController.setUserControlling(false)
                    requestResumeFor(controlRunId)
                },
                onStop = { if (isCurrentContent()) requestStop(controlRunId) },
                onSupplementModeChange = { if (isCurrentContent()) setCapsuleInputMode(it) },
                onSupplement = { if (isCurrentContent()) requestSupplement(controlRunId, it) },
                virtualDisplay = virtualDisplayState.value,
                onTakeOverVirtualDisplay = {
                    if (!isCurrentContent()) return@AgentOverlayCapsule
                    VirtualDisplayController.setUserControlling(true)
                    requestPause(controlRunId)
                },
                onResumeVirtualDisplay = {
                    if (!isCurrentContent()) return@AgentOverlayCapsule
                    VirtualDisplayController.setUserControlling(false)
                    requestResumeFor(controlRunId)
                },
                onFullscreenVirtualDisplay = { if (isCurrentContent()) requestFullscreenVirtualDisplay() },
                onTapVirtualCanvas = { nx, ny, longPress ->
                    if (isCurrentContent()) VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                },
                onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                    if (isCurrentContent()) VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                },
                ownerKey = contentKey,
                onVisibleFrame = { if (isCurrentContent()) onCapsuleVisibleFrame(epoch, controlRunId) },
            )
        }
        val capsuleLp = capsuleLayoutParams(type)
        capsule.keepScreenOn = keepScreenOn.value
        if (!connectionCurrent()) {
            discardStale()
            return stale()
        }
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            tracePresentation("capsule_add_failed")
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            if (capsule.isAttachedToWindow) runCatching { wm.removeViewImmediate(capsule) }
            if (AgentAccessibilityService.connection.value != connection) {
                discardStale()
                return stale()
            }
            if (epoch == overlayEpoch) removeAmbientWindows()
            return ToolExecutionDecision.Reject("GUI_PRESENTATION_FAILED", "操作提示胶囊窗口建立失败，本次未执行")
        }
        if (epoch != overlayEpoch) {
            runCatching { wm.removeViewImmediate(capsule) }
            return stale()
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
        if (!connectionCurrent()) { discardStale(); return stale() }
        if (!updateCapsuleTouchability()) {
            removeAmbientWindows()
            return ToolExecutionDecision.Reject("GUI_PRESENTATION_FAILED", "操作提示窗口更新失败，本次未执行")
        }
        tracePresentation("capsule_add_ok")
        return ToolExecutionDecision.Allow
    }

    /**
     * 让路时同步切换触摸穿透：标志位立刻生效保证手势不被截走，视觉过渡交给胶囊内的动画。
     * 恢复时先淡入再收回触摸，两者顺序无关紧要，因为恢复发生在手势结束之后。
     */
    private fun setCapsuleYielded(yielded: Boolean): Boolean {
        if (!yielded) clearanceHost.cancelRestore()
        capsuleYielded.value = yielded
        presentation.setYielded(yielded)
        capsulePaintedOwner = null
        if (updateCapsuleTouchability()) return true
        removeAmbientWindows()
        return false
    }

    private fun updateCapsuleTouchability(): Boolean {
        val wm = windowManager ?: return false
        val capsule = capsuleView ?: return false
        val lp = capsuleParams ?: return false
        val guiState = guiControl.state.value
        // A delayed clearance restore must not reactivate a released or different owner's window.
        val run = displayedRun
        val live = run != null && runs.contains(run) && !run.session.isTerminal && !run.session.controller.isCancelled
        val touchable = !capsuleYielded.value && (live || guiState.userPaused) &&
            (guiState.ownerRunId == null || guiState.ownerRunId == run?.runId) &&
            capsulePaintedOwner == run?.runId && overlayConnection == AgentAccessibilityService.connection.value
        val nextFlags = if (!touchable) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return true
        lp.flags = nextFlags
        return runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            tracePresentation("capsule_touch_update_failed")
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }.isSuccess
    }

    private fun removePresentationView(view: View, kind: String) {
        val wm = windowManager ?: return
        runCatching { wm.removeViewImmediate(view) }.onSuccess {
            tracePresentation("${kind}_remove_ok")
        }.onFailure { throwable ->
            tracePresentation("${kind}_remove_failed")
            // Even if WindowManager rejected removal, a stale surface must neither consume input
            // nor continue claiming to execute an operation.
            view.visibility = View.GONE
            view.isEnabled = false
            (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                runCatching { wm.updateViewLayout(view, params) }
            }
            AndroidAgentLogger.warnThrottled("runtime_overlay_remove_failed") {
                "Agent runtime overlay removal failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        VirtualDisplayController.setStageExpanded(expand && virtualDisplayState.value.active)
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) updateCapsuleWindowBounds(expanding = true)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            VirtualDisplayController.setStageExpanded(false)
            updateCapsuleWindowBounds(expanding = false)
        }
    }

    private fun updateCapsuleWindowBounds(expanding: Boolean = capsuleExpanded.value) {
        val vdActive = virtualDisplayState.value.active
        val focusable = capsuleSupplementFocusable.value
        val widthDp = if (vdActive && expanding && !focusable) {
            CAPSULE_STAGE_WINDOW_WIDTH_DP
        } else if (vdActive) {
            CAPSULE_MICRO_WINDOW_WIDTH_DP
        } else {
            CAPSULE_WINDOW_WIDTH_DP
        }
        val heightDp = when {
            focusable -> CAPSULE_SUPPLEMENT_HEIGHT_DP
            expanding && vdActive -> CAPSULE_STAGE_HEIGHT_DP
            expanding -> CAPSULE_CONTROLS_HEIGHT_DP
            else -> CAPSULE_COLLAPSED_HEIGHT_DP
        }
        setCapsuleWindowSize(widthDp, heightDp)
    }

    private fun setCapsuleWindowSize(widthDp: Int, heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        val height = dpToPx(heightDp)
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestFullscreenVirtualDisplay() {
        capsuleExpanded.value = false
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-fullscreen-handoff", isDaemon = true) {
            VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            val resultRun = displayedRun
            AgentResultCard(
                state = state.value,
                onClose = { dismissResult(resultRun) },
                onOpenEta = { openEtaFromResult(resultRun) },
                hasVirtualDisplayApp = virtualDisplayState.value.active &&
                    virtualDisplayState.value.packageName.isNotBlank(),
                onOpenVirtualApp = {
                    thread(name = "eta-vd-result-handoff", isDaemon = true) {
                        VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
                        mainHandler.post { dismissResult(resultRun) }
                    }
                },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            tracePresentation("result_add_failed")
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        tracePresentation("result_add_ok")
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    /**
     * 回到 Eta 本体并定位到本次任务的会话。卡片窗口可见时属于前台可见的用户操作，
     * 允许从服务启动 Activity；启动失败时保留卡片，不让用户丢失结果。
     */
    private fun openEtaFromResult(run: RuntimeRunRegistry.Run?) {
        val handoff = run?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissResult(run)
    }

    private fun dismissResult(run: RuntimeRunRegistry.Run?) {
        if (run != null && displayedRun === run && lastGuiOwnerRunId == run.runId &&
            guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused) dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val resultRun = displayedRun
        val callback = OnBackInvokedCallback { dismissResult(resultRun) }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(context: Context = overlayContext(), content: @Composable () -> Unit): ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            if (!suppressOverlayCompositionForTest) {
                setContent {
                    MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                        // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                        // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                        CompositionLocalProvider(LocalSquircleEnabled provides false) {
                            content()
                        }
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大：
     * 展开前先扩到目标档，收起动画结束后再缩回，见 [setCapsuleWindowHeight]。
     */
    private fun capsuleLayoutParams(type: Int = overlayType()): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(if (virtualDisplayState.value.active) CAPSULE_MICRO_WINDOW_WIDTH_DP else CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 贴在状态栏下方居中：远离多数应用的主要点击区，也和系统胶囊、流体云的位置习惯一致。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(type: Int = overlayType()): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            type,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        capsuleSupplementFocusable.value = focusable
        updateCapsuleWindowBounds()
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        if (!focusable) {
            capsule.clearFocus()
            runCatching {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(capsule.windowToken, 0)
            }.onFailure { tracePresentation("capsule_keyboard_hide_failed") }
        }
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            tracePresentation("capsule_focus_update_failed")
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
            removeAmbientWindows()
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState) {
        if (guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused &&
            taskPausedRuns.any { id -> id != displayedRun?.runId && runs.get(id)?.let {
                !it.session.isTerminal && !it.session.controller.isCancelled && it.session.controller.isPaused
            } == true }) {
            restorePausedTaskControl()
            return
        }
        displayedRun?.runId?.let { presentation.terminal(it); taskPausedRuns.remove(it) }
        operationPresentationRunId = null
        restoreAfterReconnect = false
        releaseVisual?.let(mainHandler::removeCallbacks)
        releaseVisual = null
        state.value = finalState

        val run = displayedRun
        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = run?.hasExecutedForegroundTool == true,
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            // 撤掉胶囊和边缘光，改显半屏结果卡片；无交互时由卡片自身延时退场并回调关闭
            capsuleExpanded.value = false
            removeAmbientWindows()
            if (windowManager == null && (AgentAccessibilityService.current() != null || Settings.canDrawOverlays(this))) {
                windowManager = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            windowManager?.let(::showResultCard) ?: dismissAndStop()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        clearanceHost.cancelRestore()
        overlayEpoch++
        overlayConnection = null
        clearanceIdentity = Any()
        capsulePaintedOwner = null
        capsuleYielded.value = false
        presentation.setYielded(false)
        presentation.forgetVisible()
        capsuleView?.keepScreenOn = false
        capsuleView?.let { removePresentationView(it, "capsule") }
        glowView?.let { removePresentationView(it, "glow") }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        // stopSelf 不会销毁仍被客户端绑定的服务；窗口关闭后不再保留完整请求与结果历史。
        displayedRun?.completedContext = null
        displayedRun = null
        removeOverlayWindows()
        if (runs.all().isEmpty() && !guiControl.state.value.userPaused) {
            VirtualDisplayController.release(this)
            stopSelf()
        }
    }

    private fun removeOverlayWindows() {
        restoreAfterReconnect = false
        blockedPresentationRunId = null
        operationPresentationRunId = null
        mainHandler.removeCallbacksAndMessages(hideToken)
        releaseVisual?.let(mainHandler::removeCallbacks)
        releaseVisual = null
        presentation.clear()
        presentationRunId.value = null
        capsuleOwnerKey.value = ""
        unregisterResultCardBack()
        resultCardView?.let { removePresentationView(it, "result") }
        removeAmbientWindows()
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    companion object {
        @Volatile
        private var activeInstance: AgentRuntimeService? = null

        // 只投影到 App 与 GUI 浮层的现有窗口，窗口不可见时由系统恢复息屏。
        private val keepScreenOnState = MutableStateFlow(false)
        val keepScreenOn = keepScreenOnState.asStateFlow()

        internal fun onEtaUiVisibilityChanged() {
            val instance = activeInstance ?: return
            instance.mainHandler.post { instance.handleEtaUiVisibilityChanged() }
        }

        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_MICRO_WINDOW_WIDTH_DP = 290
        private const val CAPSULE_STAGE_WINDOW_WIDTH_DP = 324
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_STAGE_HEIGHT_DP = 524
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
