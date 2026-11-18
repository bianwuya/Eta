package io.github.mangi.eta.ui.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class KimiWebPhase { CHECKING, READY, STARTING, RUNNING, STOPPING, FAILED }

internal data class KimiWebUiState(
    val target: KimiWebTarget? = null,
    val phase: KimiWebPhase = KimiWebPhase.CHECKING,
    val errorCode: String? = null,
    val hasTask: Boolean = false,
) {
    val canLaunch: Boolean get() = phase in setOf(KimiWebPhase.READY, KimiWebPhase.RUNNING, KimiWebPhase.FAILED)
    val canStop: Boolean get() = phase == KimiWebPhase.STARTING || (hasTask && phase != KimiWebPhase.STOPPING)

    fun withStatus(status: KimiWebRuntimeStatus, errorCode: String? = status.code): KimiWebUiState = copy(
        phase = when {
            errorCode != null -> KimiWebPhase.FAILED
            status.ready -> KimiWebPhase.RUNNING
            else -> KimiWebPhase.READY
        },
        errorCode = errorCode,
        hasTask = status.canStop,
    )
}

/** Main-thread owner shared by the Linux page and the home overflow menu. */
internal class KimiWebController(private val scope: CoroutineScope, private val runtime: KimiWebRuntime) {
    private val mutableState = MutableStateFlow(KimiWebUiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var actionJob: Job? = null
    private var actionVersion = 0L
    private var lastActionError: String? = null

    fun select(target: KimiWebTarget) {
        if (mutableState.value.target == target) return
        actionVersion++
        lastActionError = null
        refreshJob?.cancel()
        actionJob?.cancel()
        refreshJob = null
        actionJob = null
        mutableState.value = KimiWebUiState(target)
    }

    fun refresh(target: KimiWebTarget) {
        if (mutableState.value.target != target || actionJob?.isActive == true || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val status = readStatus(target)
            ensureActive()
            if (status.ready) lastActionError = null
            mutableState.value = mutableState.value.withStatus(status, status.code ?: lastActionError)
        }
    }

    fun launch(target: KimiWebTarget, onFailure: (String) -> Unit) {
        val current = mutableState.value
        if (current.target != target || !current.canLaunch || actionJob?.isActive == true) return
        startAction(current, KimiWebPhase.STARTING) {
            val result = try {
                runtime.launch(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                KimiWebLaunchResult.Failed("START_FAILED")
            }
            // Recheck after browser handoff: a process that just exited must not be shown as running.
            val status = readStatus(target)
            val launchError = (result as? KimiWebLaunchResult.Failed)?.code
            applyResult(status, launchError, onFailure)
        }
    }

    fun stop(target: KimiWebTarget, onFailure: (String) -> Unit) {
        val current = mutableState.value
        if (current.target != target || !current.canStop) return
        val previous = actionJob
        startAction(current, KimiWebPhase.STOPPING) {
            previous?.cancel()
            previous?.join()
            val status = try {
                runtime.stop(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                KimiWebRuntimeStatus(canStop = current.hasTask, code = "STOP_FAILED")
            }
            applyResult(status, status.code, onFailure)
        }
    }

    private fun startAction(state: KimiWebUiState, phase: KimiWebPhase, block: suspend () -> Unit) {
        val version = ++actionVersion
        lastActionError = null
        refreshJob?.cancel()
        mutableState.value = state.copy(phase = phase, errorCode = null)
        actionJob = scope.launch {
            try {
                block()
            } finally {
                // User-lease cancellation has no result; only this exact action can clear its busy label.
                if (version == actionVersion && mutableState.value.phase == phase) {
                    mutableState.value = state
                }
            }
        }
    }

    private suspend fun applyResult(status: KimiWebRuntimeStatus, actionError: String?, onFailure: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        lastActionError = actionError?.takeUnless { it == "ENVIRONMENT_CHANGED" }
        val error = actionError ?: status.code
        mutableState.value = mutableState.value.withStatus(status, error)
        error?.takeUnless { it == "ENVIRONMENT_CHANGED" }?.let(onFailure)
    }

    private suspend fun readStatus(target: KimiWebTarget): KimiWebRuntimeStatus = try {
        runtime.status(target)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        KimiWebRuntimeStatus(code = "PROCESS_NOT_CONFIRMED")
    }
}
