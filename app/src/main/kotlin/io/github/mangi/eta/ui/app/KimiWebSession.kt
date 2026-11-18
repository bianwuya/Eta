package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class KimiWebTarget(val distribution: LinuxDistribution, val backend: LinuxExecutionBackend) {
    val identity: String get() = if (backend == LinuxExecutionBackend.PROOT) "user" else "root"
}

internal data class KimiWebRuntimeStatus(
    val canStop: Boolean = false,
    val ready: Boolean = false,
    val code: String? = null,
)

internal sealed interface KimiWebLaunchResult {
    data object Opened : KimiWebLaunchResult
    data class Failed(val code: String) : KimiWebLaunchResult
}

internal interface KimiWebRuntime {
    suspend fun launch(target: KimiWebTarget): KimiWebLaunchResult
    suspend fun status(target: KimiWebTarget): KimiWebRuntimeStatus
    suspend fun stop(target: KimiWebTarget): KimiWebRuntimeStatus
}

/** Web 会话不安装 CLI；浏览器没打开时仅回收本次新建的守护任务。 */
internal class KimiWebSession(
    private val tasks: Tasks,
    private val isReachable: (Int) -> Boolean,
    private val openUrl: (String) -> Boolean,
    private val waitAttempts: Int = 30,
    private val waitIntervalMs: Long = 500,
) {
    interface Tasks {
        fun list(): List<DetachedTaskStatus>
        suspend fun start(target: KimiWebTarget): DaemonStartResult
        fun logs(id: String): DaemonLogsResult
        fun confirmRunning(task: DetachedTask): Boolean
        fun stop(id: String): Boolean
    }

    private fun find(target: KimiWebTarget): DetachedTaskStatus? {
        val matches = tasks.list().filter { (task, _) ->
            !task.commandTask && task.environment == target.distribution.terminalEnvironment &&
                task.backend == target.backend && task.identity == target.identity &&
                task.command.trim() in setOf(COMMAND, "kimi web")
        }
        return matches.lastOrNull { it.running } ?: matches.lastOrNull()
    }

    suspend fun launch(target: KimiWebTarget, stillSelected: () -> Boolean = { true }): KimiWebLaunchResult {
        var createdTaskId: String? = null
        var opened = false
        try {
            val existing = find(target)?.takeIf { it.running }?.task
            val task = existing ?: when (val result = tasks.start(target)) {
                is DaemonStartResult.Started -> result.task.also { createdTaskId = it.id }
                is DaemonStartResult.Failed -> return KimiWebLaunchResult.Failed(result.code)
            }
            var lastCode = "URL_TIMEOUT"
            return withTimeoutOrNull(20_000) {
                repeat(waitAttempts) { index ->
                    if (!stillSelected()) return@withTimeoutOrNull KimiWebLaunchResult.Failed("ENVIRONMENT_CHANGED")
                    if (!tasks.confirmRunning(task)) return@withTimeoutOrNull KimiWebLaunchResult.Failed("PROCESS_NOT_CONFIRMED")
                    val logs = tasks.logs(task.id)
                    val address = if (logs.ok) addressFromLogs(logs.text) else null
                    lastCode = if (logs.ok) "URL_TIMEOUT" else logs.code.ifBlank { "LOGS_UNAVAILABLE" }
                    if (address != null && isReachable(address.port) && tasks.confirmRunning(task)) {
                        currentCoroutineContext().ensureActive()
                        if (!stillSelected()) return@withTimeoutOrNull KimiWebLaunchResult.Failed("ENVIRONMENT_CHANGED")
                        opened = openUrl(address.url)
                        return@withTimeoutOrNull if (opened) KimiWebLaunchResult.Opened
                        else KimiWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                    }
                    if (index + 1 < waitAttempts) delay(waitIntervalMs)
                }
                KimiWebLaunchResult.Failed(lastCode)
            } ?: KimiWebLaunchResult.Failed(lastCode)
        } finally {
            if (!opened) createdTaskId?.let(tasks::stop)
        }
    }

    fun status(target: KimiWebTarget): KimiWebRuntimeStatus {
        val match = find(target) ?: return KimiWebRuntimeStatus()
        if (!match.running) return KimiWebRuntimeStatus(code = "KIMI_EXITED")
        if (!tasks.confirmRunning(match.task)) return KimiWebRuntimeStatus(canStop = true, code = "PROCESS_NOT_CONFIRMED")
        val logs = tasks.logs(match.task.id)
        if (!logs.ok) return KimiWebRuntimeStatus(canStop = true, code = logs.code.ifBlank { "LOGS_UNAVAILABLE" })
        val address = addressFromLogs(logs.text)
        if (address == null || !isReachable(address.port)) return KimiWebRuntimeStatus(canStop = true, code = "URL_TIMEOUT")
        return if (tasks.confirmRunning(match.task)) KimiWebRuntimeStatus(canStop = true, ready = true)
        else KimiWebRuntimeStatus(canStop = true, code = "PROCESS_NOT_CONFIRMED")
    }

    fun stop(target: KimiWebTarget): KimiWebRuntimeStatus {
        val task = find(target)?.task ?: return KimiWebRuntimeStatus()
        return if (tasks.stop(task.id)) status(target)
        else KimiWebRuntimeStatus(canStop = true, code = "STOP_FAILED")
    }

    // 启动日志中的 token 只交给外部浏览器。不写入 UI 状态、Toast 或诊断日志。
    internal class LocalAddress(val url: String, val port: Int)

    companion object {
        const val COMMAND = "kimi web --no-open"
        private val ANSI_COLOR = Regex("""\u001b\[[0-9;:]*m""")
        private val WEB_URL = Regex("""(?:^|[\s\u001b\]])(http://127\.0\.0\.1:([0-9]{1,5})/#token=[A-Za-z0-9_-]+)(?=$|[\s\u001b])""")

        fun addressFromLogs(text: String): LocalAddress? = WEB_URL.findAll(ANSI_COLOR.replace(text, "")).mapNotNull { match ->
            val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            LocalAddress(match.groupValues[1], port)
        }.lastOrNull()
    }
}
