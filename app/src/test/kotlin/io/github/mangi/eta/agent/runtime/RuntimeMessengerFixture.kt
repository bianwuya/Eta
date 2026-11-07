package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Real service/registry and client Messenger protocol; only Android binding is supplied by the fixture. */
internal class RuntimeMessengerFixture : AutoCloseable {
    private val host = Robolectric.buildService(AgentRuntimeService::class.java).create()
    val service = host.get()
    val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }
        .get(service) as RuntimeRunRegistry
    private val incoming = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
        .getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }.newInstance(service) as Handler
    val messages = java.util.Collections.synchronizedList(mutableListOf<Int>())
    var beforeDispatch: (Message) -> Boolean = { true }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            messages += msg.what
            msg.sendingUid = Process.myUid()
            if (beforeDispatch(msg)) incoming.handleMessage(msg)
        }
    })
    val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(ComponentName(this, AgentRuntimeService::class.java), messenger.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
    }
    val client = AgentRuntimeClient(context, AndroidAgentLogger)
    init {
        io.github.mangi.eta.config.Prefs.initLocal(context)
        io.github.mangi.eta.data.datastore.SettingsDataStore.init(context)
        io.github.mangi.eta.data.repository.AgentMemoryRepository.init(context)
        io.github.mangi.eta.data.repository.ProviderRepository.init(context)
        io.github.mangi.eta.data.repository.McpServerRepository.init(context)
    }
    private val workers = mutableListOf<Thread>()

    fun <T> worker(block: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        workers += Thread {
            try { result.complete(block()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }.apply { start() }
        return result
    }

    fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Runtime Messenger operation did not finish", condition())
    }

    fun <T> result(future: CompletableFuture<T>): T {
        await { future.isDone }
        return future.get(1, TimeUnit.SECONDS)
    }

    fun request(id: String, key: String = id): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        id, "fixture task", AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
            model = "fixture", systemPrompt = "", browserTools = false, deviceDirectTools = false,
            subagentTools = false, autoCompactionEnabled = false), emptyList(), handoff = AgentRuntimeWire.EntryHandoff(
            "$key:$id", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            AgentExternalArchivePayload("fixture task", key, "Task $id").toJson()),
    )

    fun admit(request: AgentRuntimeWire.RunRequest): RuntimeRunRegistry.Run {
        val method = AgentRuntimeService::class.java.declaredMethods.single { it.name == "admitRun" }
            .apply { isAccessible = true }
        return requireNotNull(method.invoke(service, request, null, null, { _: String -> }) as? RuntimeRunRegistry.Run)
    }

    fun complete(run: RuntimeRunRegistry.Run, result: AgentRuntimeWire.RunResult) {
        val persist = AgentRuntimeService::class.java.declaredMethods.single { it.name == "persistRunArtifacts" }
            .apply { isAccessible = true }
        check(run.session.complete(result) { persist.invoke(service, run.snapshotRequest(), it, emptyList<AgentEvent>()) })
    }

    override fun close() {
        workers.filter(Thread::isAlive).forEach(Thread::interrupt)
        workers.forEach { worker -> worker.join(3000); assertFalse("waiter leaked", worker.isAlive) }
        shadowOf(Looper.getMainLooper()).idle()
        AgentRuntimeConnection::class.java.getDeclaredMethod("resetDeadBinding").apply { isAccessible = true }
            .invoke(AgentRuntimeConnection)
        host.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
