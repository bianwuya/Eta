package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session tests use fake tasks, URLs, network observations and browser handoff; nothing is installed or started. */
class KimiWebLauncherTest {
    @Test fun reusesTheMatchingDaemonAfterReentryAndIgnoresOtherBackends() = runBlocking {
        val tasks = FakeTasks().apply {
            add("other", TARGET.copy(backend = LinuxExecutionBackend.CHROOT))
            add("old", TARGET, command = "kimi web")
        }
        assertTrue(session(tasks).status(TARGET).ready)
        val opened = mutableListOf<String>()
        assertEquals(KimiWebLaunchResult.Opened, session(tasks) { opened += it; true }.launch(TARGET))
        assertEquals(listOf("old"), tasks.readIds.distinct())
        assertEquals(listOf(URL), opened)
        assertEquals(0, tasks.starts)
    }

    @Test fun browserFailureDoesNotStopAnExistingDaemonButCleansUpANewOne() = runBlocking {
        for (existing in listOf(false, true)) {
            val tasks = FakeTasks().apply { if (existing) add("old", TARGET) }
            assertEquals(KimiWebLaunchResult.Failed("BROWSER_UNAVAILABLE"), session(tasks) { false }.launch(TARGET))
            assertEquals(if (existing) emptyList<String>() else listOf("new-1"), tasks.stops)
        }
    }

    @Test fun earlyExitAndUnconfirmedOwnerAreNeverAdvertisedAsRunning() = runBlocking {
        val tasks = FakeTasks().apply { owner = false }
        assertEquals(KimiWebLaunchResult.Failed("PROCESS_NOT_CONFIRMED"), session(tasks).launch(TARGET))
        assertEquals(listOf("new-1"), tasks.stops)
        tasks.add("old", TARGET, running = false)
        assertEquals("KIMI_EXITED", session(tasks).status(TARGET).code)
        tasks.add("uncertain", TARGET)
        assertFalse(session(tasks).status(TARGET).ready)
    }

    @Test fun staleAddressDoesNotOpenAndTimesOutOnlyTheNewTask() = runBlocking {
        val tasks = FakeTasks().apply { listening = false }
        assertEquals(KimiWebLaunchResult.Failed("URL_TIMEOUT"), session(tasks) { error("must not open") }.launch(TARGET))
        assertEquals(listOf("new-1"), tasks.stops)
        tasks.add("old", TARGET)
        assertFalse(session(tasks).status(TARGET).ready)
    }

    @Test fun logsFailureIsNotAReadyStatusAndRetainsTheStopAction() {
        val tasks = FakeTasks().apply { add("old", TARGET); logs = DaemonLogsResult(ok = false) }
        val status = session(tasks).status(TARGET)
        assertFalse(status.ready)
        assertTrue(status.canStop)
        assertEquals("LOGS_UNAVAILABLE", status.code)
    }

    @Test fun missingCommandReportsFailureWithoutOpeningAnything() = runBlocking {
        val tasks = FakeTasks().apply { startError = "KIMI_COMMAND_MISSING" }
        assertEquals(KimiWebLaunchResult.Failed("KIMI_COMMAND_MISSING"), session(tasks) { error("must not open") }.launch(TARGET))
        assertTrue(tasks.entries.isEmpty())
        assertTrue(tasks.stops.isEmpty())
    }

    @Test fun cancellationAndEnvironmentSwitchOnlyCleanUpTheNewTask() = runBlocking {
        for (existing in listOf(false, true)) {
            val tasks = FakeTasks().apply { if (existing) add("old", TARGET) }
            val polling = CompletableDeferred<Unit>()
            tasks.logs = DaemonLogsResult(ok = true, text = "booting")
            tasks.onLogRead = { polling.complete(Unit) }
            val launchJob = launch {
                KimiWebSession(tasks, { true }, { error("must not open") }, waitIntervalMs = 60_000).launch(TARGET)
            }
            polling.await()
            launchJob.cancelAndJoin()
            assertEquals(if (existing) emptyList<String>() else listOf("new-1"), tasks.stops)
        }
        val switched = FakeTasks().apply { add("old", TARGET) }
        assertEquals(KimiWebLaunchResult.Failed("ENVIRONMENT_CHANGED"), session(switched).launch(TARGET) { false })
        assertTrue(switched.stops.isEmpty())
    }

    @Test fun stopThenLaunchDoesNotTouchAnotherDistributionOrOrdinaryCommand() = runBlocking {
        val tasks = FakeTasks().apply {
            add("other", TARGET.copy(distribution = LinuxDistribution.ALPINE))
            add("other-command", TARGET, command = "kimi web --something-else")
            add("old", TARGET)
        }
        val session = session(tasks)
        assertEquals(KimiWebRuntimeStatus(), session.stop(TARGET))
        assertEquals(listOf("old"), tasks.stops)
        assertEquals(KimiWebLaunchResult.Opened, session.launch(TARGET))
        assertEquals(1, tasks.starts)
        assertEquals(setOf("other", "other-command", "new-1"), tasks.entries.keys)
    }

    @Test fun acceptsOnlyCompleteLoopbackTokenAddressesWithAValidPort() {
        assertEquals(URL, KimiWebSession.addressFromLogs("\u001b[36m$URL\u001b[0m\n")?.url)
        assertEquals(43123, KimiWebSession.addressFromLogs(URL)?.port)
        for (bad in listOf(
            "https://127.0.0.1:43123/#token=test",
            "http://0.0.0.0:43123/#token=test",
            "http://localhost:43123/#token=test",
            "http://127.0.0.1:0/#token=test",
            "http://127.0.0.1:65536/#token=test",
            "http://127.0.0.1:43123/",
            "$URL.evil",
            "https://example.test/?next=$URL",
        )) assertNull(bad, KimiWebSession.addressFromLogs(bad))
    }

    private fun session(tasks: FakeTasks, open: (String) -> Boolean = { true }) =
        KimiWebSession(tasks, { tasks.listening }, open, waitAttempts = 1, waitIntervalMs = 0)

    private class FakeTasks : KimiWebSession.Tasks {
        val entries = linkedMapOf<String, DetachedTaskStatus>()
        val stops = mutableListOf<String>()
        val readIds = mutableListOf<String>()
        var starts = 0
        var owner = true
        var listening = true
        var startError: String? = null
        var logs = DaemonLogsResult(ok = true, text = URL)
        var onLogRead: () -> Unit = {}

        fun add(id: String, target: KimiWebTarget, command: String = KimiWebSession.COMMAND, running: Boolean = true): DetachedTask {
            val task = DetachedTask(id, 10, "fake-owner", command, "/workspace", target.identity,
                target.distribution.terminalEnvironment, "/fake.log", 0, target.backend)
            entries[id] = DetachedTaskStatus(task, running)
            return task
        }

        override fun list() = entries.values.toList()
        override suspend fun start(target: KimiWebTarget): DaemonStartResult {
            starts++
            return startError?.let { DaemonStartResult.Failed(it, "") } ?: DaemonStartResult.Started(add("new-$starts", target))
        }
        override fun logs(id: String): DaemonLogsResult { readIds += id; onLogRead(); return logs }
        override fun confirmRunning(task: DetachedTask) = owner && entries[task.id]?.running == true
        override fun stop(id: String): Boolean { stops += id; return entries.remove(id) != null }
    }

    private companion object {
        val TARGET = KimiWebTarget(LinuxDistribution.DEBIAN, LinuxExecutionBackend.PROOT)
        const val URL = "http://127.0.0.1:43123/#token=fake_token-1"
    }
}
