package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val skills = AgentSkillsStore(application, viewModelScope)
    val memory = AgentMemoryStore(application, viewModelScope)
    val permissionHealth = PermissionHealthStore(application, viewModelScope)
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
    val kimiWeb = KimiWebController(viewModelScope, KimiWebLauncher(application))
    private val initialKimiTarget = currentKimiWebTarget(application).also(kimiWeb::select)
    val kimiWebTarget = combine(
        LinuxEnvironmentSettingsRepository.selectedFlow(application),
        LinuxEnvironmentSettingsRepository.backendFlow(application, LinuxDistribution.ALPINE),
        LinuxEnvironmentSettingsRepository.backendFlow(application, LinuxDistribution.DEBIAN),
    ) { distribution, alpineBackend, debianBackend ->
        KimiWebTarget(distribution, if (distribution == LinuxDistribution.ALPINE) alpineBackend else debianBackend)
    }.onEach(kimiWeb::select).stateIn(viewModelScope, SharingStarted.Eagerly, initialKimiTarget)

    private fun currentKimiWebTarget(context: Application): KimiWebTarget {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        return KimiWebTarget(distribution, LinuxEnvironmentSettingsRepository.backend(context, distribution))
    }
}
