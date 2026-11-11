package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class DaemonRestartRecordTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun task() = DetachedTask(
        id = "dm_network", pid = 123, token = "ownership-token", command = "service-command",
        cwd = "/root", identity = "user", environment = TerminalEnvironment.DEBIAN,
        logPath = "/workspace/daemon/dm_network.log", startedAt = 1,
        backend = LinuxExecutionBackend.PROOT,
    )

    @Test
    fun restartOptInSurvivesRecordReload() {
        val saved = task().toRecordJson().put("restart_on_boot", true)
        val reloaded = saved.toDetachedTask().toRecordJson()
        assertTrue("An opted-in service must remain restartable after process/device restart",
            reloaded.optBoolean("restart_on_boot", false))
    }

    @Test
    fun existingServicesAreNotOptedInImplicitly() {
        val legacy = task().toRecordJson().apply { remove("restart_on_boot") }
        assertFalse(legacy.toDetachedTask().toRecordJson().optBoolean("restart_on_boot", false))
    }

    @Test fun restorationExcludesLiveServicesOrdinaryTasksAndCommands() {
        val service = task().copy(restartOnBoot = true)
        val statuses = listOf(
            DetachedTaskStatus(service, false),
            DetachedTaskStatus(service.copy(id = "live"), true),
            DetachedTaskStatus(task().copy(id = "ordinary"), false),
            DetachedTaskStatus(service.copy(id = "job", commandTask = true), false),
        )
        assertTrue(servicesToRestore(statuses) == listOf(service))
    }

    @Test fun commandRecordsCannotEnableAutomaticReplay() {
        val command = task().copy(commandTask = true).toRecordJson().put("restart_on_boot", true)
        assertFalse(command.toDetachedTask().restartOnBoot)
    }

    @Test fun configurePersistsOptInAndCanDisableWithoutChangingTheCommand() {
        val record = task()
        val file = File(temporary.root, "tasks.json").apply {
            writeText(JSONArray().put(record.toRecordJson()).toString())
        }
        val logger = object : AgentLogger {
            override fun debug(message: () -> String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }
        val host = DetachedTaskSupervisor(logger, file)
        assertFalse(host.hasRestartServices())
        assertTrue(host.configureRestart(record.id, true))
        assertTrue(DetachedTaskSupervisor(logger, file).hasRestartServices())
        assertTrue(host.findTask(record.id) == record.copy(restartOnBoot = true))
        assertTrue(host.configureRestart(record.id, false))
        assertFalse(host.hasRestartServices())
        assertFalse(host.configureRestart("missing", true))
    }
}
