package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubagentDetailStoreTest {
    @After fun close() = EtaDatabase.closeForTests()
    @Test fun completePromptAndCommandSurvivePagedStorageAndReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "detail-fixture-1"
        val prompt = "约束😀\n".repeat(3000)
        val command = "line\n".repeat(1000) + "完成"
        store.capture(id, "step1", command)
        store.save(id, SubagentActivitySnapshot(subagentId = "child", detailId = id, prompt = prompt,
            steps = listOf(SubagentStepSnapshot("step1", "tool", toolName = "terminal", detail = "preview"))))
        fun readAll(field: String): String = runBlocking {
            buildString { var page = 0; while (true) { append(store.page(id, field, page) ?: break); page++ } }
        }
        assertEquals(prompt, readAll("prompt"))
        assertEquals(command, readAll("step-step1"))
        assertEquals("step1", store.steps(id).single().id)
        assertTrue(store.hasNext(id, "prompt", 0))
        val reopened = SubagentDetailStore(context)
        assertNotNull(reopened.header(id))
        store.clear(id)
        assertNull(reopened.header(id))
    }
}
