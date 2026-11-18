package io.github.mangi.eta.ui.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.R
import kotlinx.coroutines.delay

internal data class KimiWebControls(
    val target: KimiWebTarget,
    val state: KimiWebUiState,
    val refresh: () -> Unit,
    val launch: () -> Unit,
    val stop: () -> Unit,
)

@Composable
internal fun RefreshKimiWebWhileVisible(controls: KimiWebControls, visible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, controls.target, visible) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                controls.refresh()
                delay(5_000)
            }
        }
    }
}

@get:StringRes
internal val KimiWebUiState.actionLabel: Int get() = when (phase) {
    KimiWebPhase.CHECKING -> R.string.capability_kimi_checking
    KimiWebPhase.READY -> R.string.action_launch_kimi_web
    KimiWebPhase.STARTING -> R.string.capability_kimi_preparing
    KimiWebPhase.RUNNING -> R.string.capability_kimi_open
    KimiWebPhase.STOPPING -> R.string.capability_kimi_stopping
    KimiWebPhase.FAILED -> R.string.capability_kimi_retry
}

@StringRes
internal fun kimiWebErrorLabel(code: String): Int = when (code) {
    "ROOT_REQUIRED" -> R.string.capability_kimi_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "KIMI_EXITED" -> R.string.capability_kimi_exited
    "PROCESS_NOT_CONFIRMED" -> R.string.capability_kimi_unconfirmed
    "KIMI_COMMAND_MISSING" -> R.string.capability_kimi_command_missing
    "LINUX_ENVIRONMENT_NOT_READY", "PROOT_UNAVAILABLE", "WEB_ENVIRONMENT_UNAVAILABLE" -> R.string.capability_kimi_environment
    "ENVIRONMENT_CHANGED" -> R.string.capability_kimi_checking
    "LOGS_UNAVAILABLE", "TASK_NOT_FOUND" -> R.string.capability_kimi_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_kimi_web_failed_url
    "BROWSER_UNAVAILABLE" -> R.string.linux_kimi_web_failed_browser
    "STOP_FAILED" -> R.string.capability_kimi_stop_failed
    else -> R.string.linux_kimi_web_failed_start
}
