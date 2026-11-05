package io.github.mangi.eta.local.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * Agent 任务存活期间维持 CPU 与 Wi-Fi 的工作状态。
 *
 * 前台服务只提升进程优先级，既不阻止 CPU 休眠，也不阻止 Wi-Fi 息屏省电；息屏后长流式请求
 * 会因链路空闲被链路中间设备或服务端关闭（表现为 `connection closed`）。这里的锁只覆盖任务
 * 运行期，任务结束立即释放，不常驻、不改系统设置。
 */
internal class ExecutionKeepAlive(context: Context) {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF 仍是保活 Wi-Fi 的最小可用档，替代模式不覆盖息屏省电。
    @Synchronized
    fun acquire() {
        if (wakeLock == null) wakeLock = runCatching {
            requireNotNull(powerManager) { "PowerManager unavailable" }
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { failure ->
            AndroidAgentLogger.warnThrottled("execution_wake_lock_unavailable") {
                "Execution wake lock unavailable: type=${failure.safeLogType()}"
            }
        }.getOrNull()
        if (wifiLock == null) wifiLock = runCatching {
            requireNotNull(wifiManager) { "WifiManager unavailable" }
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { failure ->
            AndroidAgentLogger.warnThrottled("execution_wifi_lock_unavailable") {
                "Execution wifi lock unavailable: type=${failure.safeLogType()}"
            }
        }.getOrNull()
    }

    @Synchronized
    fun release() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    private companion object {
        const val WAKE_TAG = "eta:agent-execution"
        const val WIFI_TAG = "eta:agent-execution"
    }
}
