package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class DeviceCapabilitiesUi(
    val root: RootAccessState,
    // 服务回调只反映 Binder 连接，不能用于判断管理器中的模块开关或 Hook 生效状态。
    val xposedConnected: Boolean,
    val tools: AgentToolCapabilities,
) {
    val accessibilityAvailable: Boolean get() = tools.accessibilityAvailable
}

/**
 * 把 [AgentToolCapabilities.capture] 挪出主线程，并合并同一时刻的重复请求。
 *
 * 每次捕获都会跨进程读通知监听授权、AppOps、无障碍保护开关，再查 PackageManager/Resources。
 * 进入设置页时，转场期间新条目的组合与 NavEntry 生命周期的 ON_RESUME 会各要一次快照，原来两次
 * 都同步跑在主线程，于是转场动画被这两组跨进程读取拖慢。现在统一后台捕获并复用最近结果：
 * 已有在途捕获就直接加入，同一转场不再叠加同样的读取。
 */
internal class CapabilityCaptureCoalescer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private var inFlight: Job? = null

    /** 最近一次捕获结果；首次捕获完成前为 null。 */
    @Volatile
    var latest: AgentToolCapabilities? = null
        private set

    /** 在后台捕获一次；可在主线程调用，不阻塞。 */
    suspend fun capture(
        context: Context,
        loader: (Context) -> AgentToolCapabilities = AgentToolCapabilities::capture,
    ): AgentToolCapabilities {
        val appContext = context.applicationContext
        val job = synchronized(lock) {
            inFlight?.takeIf(Job::isActive) ?: scope.launch(start = CoroutineStart.LAZY) {
                runCatching { loader(appContext) }.onSuccess { latest = it }
            }.also { started ->
                inFlight = started
                started.start()
            }
        }
        job.join()
        return latest ?: localOnlySnapshot()
    }
}

/** 进程级共享实例：设置、工具、增强、Linux 环境等页面共用同一份快照。 */
internal val deviceCapabilitiesSnapshot = CapabilityCaptureCoalescer()

/**
 * 只含进程内已知事实的能力快照；用于后台捕获完成前的那一帧。
 *
 * root 状态、框架 binder 连接、无障碍服务实例归属都在本进程内判定，不产生额外 IPC；
 * 需要跨进程查询的授权位保持数据类的乐观默认值，等待后台捕获覆盖。
 */
internal fun localOnlySnapshot(): AgentToolCapabilities {
    val root = RootAccess.state.value
    return AgentToolCapabilities(
        rootAvailable = root.isGranted,
        rootState = root,
        lsposedAvailable = EtaApp.serviceInstance != null,
        accessibilityAvailable = AgentAccessibilityService.isAvailable(),
        colorOs = AgentToolCapabilities.isColorOsDevice(),
    )
}

@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    val root by RootAccess.state.collectAsState()
    var xposedConnected by remember { mutableStateOf(EtaApp.serviceInstance != null) }
    // 组合阶段只取进程内事实与已缓存的快照，跨进程读取全部留给后台捕获。
    var tools by remember { mutableStateOf(deviceCapabilitiesSnapshot.latest ?: localOnlySnapshot()) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner, context) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                xposedConnected = service != null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                scope.launch { tools = deviceCapabilitiesSnapshot.capture(context) }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        owner.lifecycle.addObserver(observer)
        onDispose {
            EtaApp.removeServiceStateListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(context) {
        tools = deviceCapabilitiesSnapshot.capture(context)
    }
    return DeviceCapabilitiesUi(root, xposedConnected, tools.copy(rootAvailable = root.isGranted))
}

internal fun RootAccessState.description(context: Context): String = context.getString(
    when {
        isChecking -> R.string.capability_root_checking
        status == RootAccessStatus.GRANTED -> R.string.capability_root_granted
        status == RootAccessStatus.UNAVAILABLE -> R.string.capability_root_unavailable
        status == RootAccessStatus.TIMED_OUT -> R.string.capability_root_timeout
        else -> R.string.capability_root_not_granted
    },
)
