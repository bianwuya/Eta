package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 设备能力捕获的线程与合并约定。
 *
 * 这些断言对应设置页转场卡顿的根因：捕获必须能在主线程安全地发起（不阻塞），并且同一时刻
 * 的重复请求只能真正执行一次读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceCapabilitiesSnapshotTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun concurrentCapturesShareOneLoadAndCacheTheResult() = runBlocking {
        val loads = AtomicInteger(0)
        val gate = CountDownLatch(1)
        val coalescer = CapabilityCaptureCoalescer()
        val loader: (Context) -> AgentToolCapabilities = {
            loads.incrementAndGet()
            gate.await(5, TimeUnit.SECONDS)
            AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true, nfcAvailable = false)
        }

        val first = async(Dispatchers.Default) { coalescer.capture(context, loader) }
        val second = async(Dispatchers.Default) { coalescer.capture(context, loader) }
        // 让两次调用都进入 join 后再放行 loader。
        Thread.sleep(50)
        gate.countDown()
        val results = awaitAll(first, second)

        assertEquals("同一时刻的重复捕获必须合并成一次读取", 1, loads.get())
        assertTrue(results[0].lsposedAvailable)
        assertEquals(false, results[0].nfcAvailable)
        assertEquals(results[0], coalescer.latest)
    }

    @Test
    fun captureDoesNotRunTheLoaderOnTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val loaderThread = CompletableDeferred<Thread>()
        val coalescer = CapabilityCaptureCoalescer()
        coalescer.capture(context) {
            loaderThread.complete(Thread.currentThread())
            AgentToolCapabilities(rootAvailable = false)
        }

        val worker = loaderThread.await()
        assertTrue("捕获必须在后台线程执行", worker !== caller)
        assertEquals("加载结果需成为可复用快照", true, coalescer.latest != null)
    }

    @Test
    fun loaderFailureKeepsPreviousSnapshotAndFallsBackWithoutCrossProcessReads() = runBlocking {
        val coalescer = CapabilityCaptureCoalescer()
        val good = coalescer.capture(context) { AgentToolCapabilities(rootAvailable = false, nfcAvailable = false) }
        assertEquals(false, good.nfcAvailable)

        val recovered = coalescer.capture(context) { error("boom") }
        assertEquals("失败不应清掉已成功的快照", false, recovered.nfcAvailable)
        assertEquals(false, coalescer.latest?.nfcAvailable)
    }

    @Test
    fun localOnlySnapshotSkipsCapabilityProbesButKeepsDeviceFacts() {
        // 首次捕获完成前的那一帧只能是纯本地事实：不得把跨进程查询结果伪装成默认可用。
        val snapshot = localOnlySnapshot()
        assertNotNull(snapshot)
        assertEquals(AgentToolCapabilities.isColorOsDevice(), snapshot.colorOs)
        assertTrue("占位快照不应声称已连接框架", !snapshot.lsposedAvailable)
    }

    @Test
    fun sequentialCapturesStillObserveCapabilityChanges() = runBlocking {
        // 合并只针对同一时刻的在途请求；授权状态变化后必须能重新读取到新结果。
        val coalescer = CapabilityCaptureCoalescer()
        assertEquals(false, coalescer.capture(context) { AgentToolCapabilities(rootAvailable = false, nfcAvailable = false) }.nfcAvailable)
        assertEquals(true, coalescer.capture(context) { AgentToolCapabilities(rootAvailable = false, nfcAvailable = true) }.nfcAvailable)
        assertEquals(true, coalescer.latest?.nfcAvailable)
    }

    @Test
    fun freshCoalescerHasNoSnapshotUntilFirstCapture() {
        assertNull(CapabilityCaptureCoalescer().latest)
        assertNotNull(deviceCapabilitiesSnapshot)
    }
}
