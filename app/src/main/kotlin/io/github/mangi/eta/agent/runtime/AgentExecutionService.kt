package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.local.runtime.ExecutionKeepAlive
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.agent.terminal.TerminalDaemonRecovery
import java.util.concurrent.atomic.AtomicLong

/** Holds execution leases; only explicitly opted-in terminal services may restart. */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val keepAlive by lazy(LazyThreadSafetyMode.NONE) { ExecutionKeepAlive(this) }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
            // 前台服务只保证进程优先级；息屏后的 CPU 休眠与 Wi-Fi 省电仍会掐断模型长连接。
            keepAlive.acquire()
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) TerminalDaemonRecovery.request(this)
        if (intent?.action == ACTION_STOP_RUN) {
            val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
            val token = intent.getStringExtra(EXTRA_LEASE_TOKEN).orEmpty()
            val stop = if (runId.isBlank() || token.isBlank()) null else leases.requestStop("run:$runId", token)
            if (stop != null) stopQueue.submit(listOf(stop)) {
                mainHandler.post { if (instance === this) refreshNotification() }
            }
            else refreshNotification()
        } else if (intent?.action == ACTION_STOP) {
            // Old all-task PendingIntents outlive notification updates: deliberately no-op.
            refreshNotification()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return if (TerminalDaemonRecovery.hasServices(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        keepAlive.release()
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            keepAlive.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val run = RuntimeNotificationSelection.select(activeRunSnapshots)
        val status = runStatus
        val conversationIdSnapshot = currentConversationId
        val archivedSourceSnapshot = currentArchivedSource
        val archivedKeySnapshot = currentArchivedKey
        val startedAtSnapshot = currentStartedAtEpochMs

        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    run != null -> {
                        action = MainActivity.ACTION_OPEN_RUN
                        data = Uri.Builder().scheme("eta-task").authority("open").appendPath(run.runId).build()
                        putExtra(MainActivity.EXTRA_RUN_ID, run.runId)
                    }
                    conversationIdSnapshot.isNotBlank() -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationIdSnapshot)
                    }
                    archivedSourceSnapshot.isNotBlank() && archivedKeySnapshot.isNotBlank() -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archivedSourceSnapshot)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archivedKeySnapshot)
                    }
                }
            }
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        val publicNotification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(getString(R.string.execution_title))
            .setOngoing(true).build()
        val contentText = executionText(this, status, taskCount, run)
        val headline = executionHeadline(this, status, taskCount)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(run?.let(::notificationRunTitle) ?: headline)
            .setContentText(contentText)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val stopToken = run?.let { leases.token("run:${it.runId}") }
        if (status != null) {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_action_open), open).build())
            if (run != null && stopToken != null) {
                val stopIntent = Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP_RUN)
                    .setData(Uri.Builder().scheme("eta-task").authority("stop").appendPath(run.runId).appendPath(stopToken).build())
                    .putExtra(EXTRA_RUN_ID, run.runId).putExtra(EXTRA_LEASE_TOKEN, stopToken)
                val runStop = PendingIntent.getService(this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(null, getString(R.string.task_stop), runStop).build())
            } else {
                builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_action_stop), stop).build())
            }
        } else {
            builder
                .setContentText(resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount))
                .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        }

        // GUI 运行或虚拟屏微缩镜显示期间退回普通前台通知，避免顶部同时出现两个胶囊冲突。
        if (status != null && !suppressLiveUpdate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder
                .setStyle(Notification.BigTextStyle().bigText(""))
                .setShortCriticalText(when {
                    run?.phase == AgentRuntimeWire.RunPhase.Committing -> phaseText(this, run)
                    status == AgentOverlayStatus.Stopping -> status.shortText()
                    run != null -> phaseText(this, run)
                    else -> status.shortText()
                })
            if (startedAtSnapshot > 0L) {
                builder
                    .setWhen(startedAtSnapshot)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
            }
            // 请求方法从 API 36.1 才公开；标准 extra 也可供支持该协议的 API 36 系统读取。
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        } else if (status != null) {
            builder.setContentText(resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount))
        }
        return builder.build()
    }

    private fun notificationRunTitle(run: AgentRuntimeWire.ActiveRunSnapshot): String {
        // Presentation titles can be raw prompts or filenames; never send them to system UI.
        val label = when (run.handoffSource) {
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE -> getString(R.string.task_source_assistant)
            AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE -> getString(R.string.task_source_chat)
            else -> getString(R.string.task_source_external)
        }
        val id = java.security.MessageDigest.getInstance("SHA-256")
            .digest(run.runId.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
        return "$label · $id"
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，具体目标留在应用内。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP_RUN = "io.github.mangi.eta.action.STOP_USER_RUN"
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_LEASE_TOKEN = "lease_token"
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private const val BRAND_COLOR = 0xFF4285F4.toInt()
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var activeRunSnapshots: List<AgentRuntimeWire.ActiveRunSnapshot> = emptyList()
        private var lastNotificationAt = 0L
        private val delayedNotification = Runnable {
            instance?.takeIf { it.foregroundActive }?.refreshNotification()
            lastNotificationAt = SystemClock.elapsedRealtime()
        }
        private var currentConversationId: String = ""
        private var currentArchivedSource: String = ""
        private var currentArchivedKey: String = ""
        private var currentStartedAtEpochMs: Long = 0L
        private var suppressLiveUpdate: Boolean = false
        private var publishedSignature: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在离散展示字段变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(
            context: Context,
            status: AgentOverlayStatus?,
            activeRunCount: Int,
            snapshots: List<AgentRuntimeWire.ActiveRunSnapshot> = emptyList(),
            conversationId: String = "",
            archivedSource: String = "",
            archivedKey: String = "",
            startedAtEpochMs: Long = 0L,
            suppressLiveUpdate: Boolean = false,
        ) {
            mainHandler.post {
                val previousCount = this.activeRunCount
                runStatus = status
                this.activeRunCount = activeRunCount
                val previousRuns = this.activeRunSnapshots
                this.activeRunSnapshots = snapshots
                currentConversationId = conversationId
                currentArchivedSource = archivedSource
                currentArchivedKey = archivedKey
                currentStartedAtEpochMs = startedAtEpochMs
                this.suppressLiveUpdate = suppressLiveUpdate
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val selected = RuntimeNotificationSelection.select(snapshots)
                val previousSelected = RuntimeNotificationSelection.select(previousRuns)
                val headline = executionHeadline(context, status, count)
                val signature = "$headline|$conversationId|$archivedSource|$archivedKey|$count|${status != null}|$startedAtEpochMs|$suppressLiveUpdate|" +
                    snapshots.joinToString { "${it.runId}:${it.handoffSource}:${it.guiStatus}:${it.phase}:${it.interactionAt}" }
                if (signature == publishedSignature) return@post
                publishedSignature = signature
                val critical = previousCount != activeRunCount ||
                    previousSelected?.runId != selected?.runId || previousSelected?.phase != selected?.phase ||
                    status == AgentOverlayStatus.Stopping ||
                    previousRuns.map { it.runId to it.guiStatus } != snapshots.map { it.runId to it.guiStatus }
                val wait = (2_000L - (SystemClock.elapsedRealtime() - lastNotificationAt)).coerceAtLeast(0)
                mainHandler.removeCallbacks(delayedNotification)
                if (critical || wait == 0L) delayedNotification.run()
                else mainHandler.postDelayed(delayedNotification, wait)
            }
        }

        private fun phaseText(context: Context, run: AgentRuntimeWire.ActiveRunSnapshot): String = context.getString(when (run.phase) {
            AgentRuntimeWire.RunPhase.Preparing -> R.string.task_preparing
            AgentRuntimeWire.RunPhase.WaitingForGui -> R.string.run_waiting_for_gui
            AgentRuntimeWire.RunPhase.Paused -> R.string.task_paused
            AgentRuntimeWire.RunPhase.Committing -> R.string.task_archiving
            AgentRuntimeWire.RunPhase.Running -> R.string.task_running
        })

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int,
            run: AgentRuntimeWire.ActiveRunSnapshot? = null): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            val statusText = when {
                run?.phase == AgentRuntimeWire.RunPhase.Committing -> phaseText(context, run)
                status == AgentOverlayStatus.Stopping -> status.localizedText(context.resources)
                run != null && run.phase != AgentRuntimeWire.RunPhase.Running -> phaseText(context, run)
                else -> when (status) {
                is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning ->
                    context.getString(R.string.execution_chip_running)
                null -> null
                else -> status.localizedText(context.resources)
                }
            }
            return when {
                statusText == null -> summary
                taskCount <= 1 -> statusText
                else -> "$summary · $statusText"
            }
        }

        private fun executionHeadline(
            context: Context,
            status: AgentOverlayStatus?,
            taskCount: Int,
        ): String {
            if (status == null) return context.getString(R.string.execution_title)
            val statusLabel = status.localizedText(context.resources)
            return if (taskCount > 1) "$statusLabel · $taskCount" else statusLabel
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
