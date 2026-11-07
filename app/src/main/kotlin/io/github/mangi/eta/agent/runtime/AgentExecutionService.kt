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
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
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
        return START_NOT_STICKY
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
        val openIntent = Intent(this, MainActivity::class.java)
        if (run != null) {
            openIntent.action = MainActivity.ACTION_OPEN_RUN
            openIntent.data = Uri.Builder().scheme("eta-task").authority("open").appendPath(run.runId).build()
            openIntent.putExtra(MainActivity.EXTRA_RUN_ID, run.runId)
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
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when {
                run != null -> notificationRunTitle(run)
                else -> getString(R.string.execution_title)
            })
            .setContentText(executionText(this, status, taskCount, run))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val stopToken = run?.let { leases.token("run:${it.runId}") }
        if (run != null && stopToken != null) {
            val stopIntent = Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP_RUN)
                .setData(Uri.Builder().scheme("eta-task").authority("stop").appendPath(run.runId).appendPath(stopToken).build())
                .putExtra(EXTRA_RUN_ID, run.runId).putExtra(EXTRA_LEASE_TOKEN, stopToken)
            val stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.task_stop), stop).build())
        }
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        if (status != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(when {
                    run?.phase == AgentRuntimeWire.RunPhase.Committing -> phaseText(this, run)
                    status == AgentOverlayStatus.Stopping -> status.shortText()
                    run != null -> phaseText(this, run)
                    else -> status.shortText()
                })
            // 请求方法从 API 36.1 才公开；标准 extra 也可供支持该协议的 API 36 系统读取。
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
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

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
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
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var activeRunSnapshots: List<AgentRuntimeWire.ActiveRunSnapshot> = emptyList()
        private var publishedStatusText: String? = null
        private var lastNotificationAt = 0L
        private val delayedNotification = Runnable {
            instance?.takeIf { it.foregroundActive }?.refreshNotification()
            lastNotificationAt = SystemClock.elapsedRealtime()
        }

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?, activeRunCount: Int,
            snapshots: List<AgentRuntimeWire.ActiveRunSnapshot> = emptyList()) {
            mainHandler.post {
                val previousCount = this.activeRunCount
                runStatus = status
                this.activeRunCount = activeRunCount
                val previousRuns = this.activeRunSnapshots
                this.activeRunSnapshots = snapshots
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val selected = RuntimeNotificationSelection.select(snapshots)
                val previousSelected = RuntimeNotificationSelection.select(previousRuns)
                val text = executionText(context, status, count, selected) +
                    snapshots.joinToString { "${it.runId}:${it.handoffSource}:${it.guiStatus}:${it.phase}:${it.interactionAt}" }
                if (text == publishedStatusText) return@post
                publishedStatusText = text
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
