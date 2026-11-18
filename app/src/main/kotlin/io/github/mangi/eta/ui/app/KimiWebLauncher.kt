package io.github.mangi.eta.ui.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.ETA_PROCESS_OWNER_ENV
import io.github.mangi.eta.agent.terminal.InstallerShellRunner
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Adapts the existing terminal/daemon host; never provisions or upgrades a CLI. */
internal class KimiWebLauncher(context: Context) : KimiWebRuntime {
    private val appContext = context.applicationContext

    override suspend fun launch(target: KimiWebTarget): KimiWebLaunchResult = operations.withLock {
        withContext(Dispatchers.IO) {
            problem(target)?.let { return@withContext KimiWebLaunchResult.Failed(it) }
            val job = currentCoroutineContext().job
            val needsLease = target.identity == "user"
            if (needsLease && !AgentExecutionService.acquire(appContext, LAUNCH_ID) { job.cancel() }) {
                return@withContext KimiWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                session(target).launch(target, stillSelected = { isSelected(target) })
            } finally {
                if (needsLease) AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    override suspend fun status(target: KimiWebTarget): KimiWebRuntimeStatus = withContext(Dispatchers.IO) {
        problem(target)?.let { return@withContext KimiWebRuntimeStatus(code = it) }
        session(target).status(target)
    }

    override suspend fun stop(target: KimiWebTarget): KimiWebRuntimeStatus = operations.withLock {
        withContext(Dispatchers.IO) {
            if (!isSelected(target)) return@withContext KimiWebRuntimeStatus(code = "ENVIRONMENT_CHANGED")
            if (target.identity == "root" && !TerminalRuntime.rootAvailable) {
                return@withContext KimiWebRuntimeStatus(code = "ROOT_REQUIRED")
            }
            session(target).stop(target)
        }
    }

    private fun isSelected(target: KimiWebTarget): Boolean =
        LinuxEnvironmentSettingsRepository.current(appContext) == target.distribution &&
            LinuxEnvironmentSettingsRepository.backend(appContext, target.distribution) == target.backend

    private fun problem(target: KimiWebTarget): String? = when {
        !isSelected(target) -> "ENVIRONMENT_CHANGED"
        target.identity == "root" && !TerminalRuntime.rootAvailable -> "ROOT_REQUIRED"
        !LinuxEnvironmentPaths.rootfsReady(rootfs(target)) -> "LINUX_ENVIRONMENT_NOT_READY"
        else -> null
    }

    private fun rootfs(target: KimiWebTarget): String =
        LinuxEnvironmentPaths.rootfsDir(appContext, target.distribution, target.backend).absolutePath

    private fun session(target: KimiWebTarget): KimiWebSession {
        val rootAccess = { target.identity == "root" && TerminalRuntime.rootAvailable }
        val supervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
            linuxRootfsPath = rootfs(target),
            rootAvailable = rootAccess,
        )
        val processSupervisor = ShellProcessSupervisor(rootAvailable = rootAccess)
        return KimiWebSession(
            tasks = object : KimiWebSession.Tasks {
                override fun list() = supervisor.list()

                override suspend fun start(target: KimiWebTarget): DaemonStartResult {
                    if (!isSelected(target)) return DaemonStartResult.Failed("ENVIRONMENT_CHANGED", "")
                    val command = InstallerShellRunner.run(
                        command = "command -v kimi >/dev/null 2>&1",
                        timeoutSeconds = 5,
                        environment = target.distribution.terminalEnvironment,
                        linuxRootfsPath = rootfs(target),
                        identity = target.identity,
                    )
                    currentCoroutineContext().ensureActive()
                    problem(target)?.let { return DaemonStartResult.Failed(it, "") }
                    return when (command.exitCode) {
                        0 -> supervisor.start(
                            command = KimiWebSession.COMMAND,
                            cwd = TerminalRuntime.LINUX_WORKSPACE_PATH,
                            identity = target.identity,
                            environment = target.distribution.terminalEnvironment,
                        )
                        1, 127 -> DaemonStartResult.Failed("KIMI_COMMAND_MISSING", "")
                        else -> DaemonStartResult.Failed("WEB_ENVIRONMENT_UNAVAILABLE", "")
                    }
                }

                override fun logs(id: String) = supervisor.readLogs(id)

                override fun confirmRunning(task: DetachedTask): Boolean {
                    // list() conservatively retains tasks if liveness is unknown. Web needs positive ownership proof.
                    if (task.pid <= 1 || task.token.isBlank() || task.identity != target.identity) return false
                    val owner = shellQuote("$ETA_PROCESS_OWNER_ENV=${task.token}")
                    return runOneShotShell(
                        processSupervisor = processSupervisor,
                        identity = task.identity,
                        command = "tr '\\000' '\\n' < /proc/${task.pid}/environ 2>/dev/null | grep -Fqx $owner",
                        timeoutSeconds = 3,
                    ).exitCode == 0
                }

                override fun stop(id: String) = supervisor.stop(id)
            },
            isReachable = ::kimiWebAcceptsLocalHttp,
            openUrl = { url ->
                try {
                    appContext.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            },
        )
    }

    private companion object {
        val operations = Mutex()
        const val LAUNCH_ID = "kimi-launch"
    }
}
