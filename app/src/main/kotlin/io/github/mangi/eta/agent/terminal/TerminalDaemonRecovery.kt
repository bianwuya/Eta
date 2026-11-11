package io.github.mangi.eta.agent.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores only explicit service opt-ins; never replays terminal jobs or agent runs. */
internal object TerminalDaemonRecovery {
    private const val LEASE = "terminal-persistent-services"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoring = AtomicBoolean(false)

    fun hasServices(context: Context): Boolean = supervisor(context).hasRestartServices()

    fun request(context: Context) {
        val app = context.applicationContext
        if (app.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        if (!hasServices(app)) {
            AgentExecutionService.release(LEASE)
            return
        }
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS) || !Prefs.isToolEnabled("terminal_daemon")) {
            scope.launch { supervisor(app).suspendRestartServices(); AgentExecutionService.release(LEASE) }
            return
        }
        if (!restoring.compareAndSet(false, true)) return
        val stopped = AtomicBoolean(false)
        if (!AgentExecutionService.acquire(app, LEASE, onStop = {
            stopped.set(true)
            supervisor(app).suspendRestartServices()
        })) {
            restoring.set(false)
            AndroidAgentLogger.warn("Terminal service recovery foreground start rejected")
            return
        }
        scope.launch {
            try {
                if (!RootAccess.isGranted) RootAccess.refresh(app).join()
                LinuxEnvironmentSettingsRepository.initialize(app)
                if (stopped.get()) return@launch
                val host = supervisor(app)
                host.restoreServices()
                if (stopped.get()) host.suspendRestartServices()
                if (host.list().none { it.task.restartOnBoot && it.running }) {
                    AgentExecutionService.release(LEASE)
                }
            } catch (failure: Exception) {
                AgentExecutionService.release(LEASE)
                if (failure is CancellationException) throw failure
                AndroidAgentLogger.warn("Terminal service recovery failed: type=${failure.javaClass.simpleName}")
            } finally {
                restoring.set(false)
            }
        }
    }

    private fun supervisor(context: Context) = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(context, it).absolutePath }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
}

class TerminalDaemonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TerminalDaemonRecovery.request(context)
        }
    }
}
