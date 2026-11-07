package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
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
class SubagentHistoryStoreTest {
    @After fun close() = EtaDatabase.closeForTests()

    private fun delta(round: Int, kind: AgentEvent.AssistantBlockKind, index: Int, value: String) =
        AgentEvent.AssistantBlockDelta(round, kind, index, value.length, value)

    private fun end(round: Int, index: Int, replacement: String? = null) =
        AgentEvent.AssistantBlockEnd(round, AgentEvent.AssistantBlockKind.TEXT, index,
            contentChars = replacement?.length ?: 0, replacementContent = replacement)

    private suspend fun body(store: SubagentDetailStore, id: String, entry: SubagentHistoryEntry): String = buildString {
        var page = 0
        while (true) append(store.page(id, "history-${entry.id}", page++) ?: break)
    }

    @Test fun incompleteHistoryWarningSurvivesReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-incomplete"
        store.save(id, SubagentActivitySnapshot(subagentId = "fixture", detailId = id, historyIncomplete = true))
        store.recordHistory(id, SubagentHistoryEntry("one", "已保存回复", "assistant", "success"), "available")
        val reopened = SubagentDetailStore(context)
        assertTrue(reopened.header(id)?.historyIncomplete == true)
        assertEquals(1, reopened.history(id).size)
        store.clear(id)
    }

    @Test fun eventOrderRetryPartialToolResultAndReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-order"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.RoundStarted(1, 1))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.THINKING, 0, "hidden-reasoning-fixture"))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.TEXT, 1, "first partial"))
        // A block can close before its request subsequently fails.
        recorder.onEvent(end(1, 1))
        recorder.onEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "MODEL_HTTP_503"))
        recorder.onEvent(AgentEvent.RoundStarted(2, 1))
        recorder.onEvent(delta(2, AgentEvent.AssistantBlockKind.TEXT, 0, "discard replacement"))
        recorder.onEvent(end(2, 0, "visible before tool"))
        recorder.onEvent(AgentEvent.ToolStarted(2, "call", "terminal", "execute", "echo fixture"))
        val call = AgentModelClient.ToolCall("call", "terminal", """{"command":"echo fixture"}""", round = 2)
        recorder.onToolResult(call, AgentModelClient.ToolResult("""{"ok":true,"stdout":"readable result"}"""))
        recorder.onEvent(AgentEvent.ToolFinished(2, "call", "terminal", "done", 0, 0, success = true))
        recorder.onEvent(AgentEvent.RoundStarted(3, 1))
        recorder.onEvent(delta(3, AgentEvent.AssistantBlockKind.TEXT, 0, "last incomplete reply"))
        recorder.finish("failed", "HTTP 503")

        val reopened = SubagentDetailStore(context)
        val entries = reopened.history(id)
        assertEquals(listOf("assistant", "retry", "assistant", "tool", "assistant", "terminal"), entries.map { it.kind })
        assertEquals("partial", entries.first().status)
        assertEquals("partial", entries[4].status)
        assertEquals("failed", entries.last().status)
        assertEquals("first partial", body(reopened, id, entries[0]))
        assertEquals("visible before tool", body(reopened, id, entries[2]))
        assertTrue(body(reopened, id, entries[3]).contains("readable result"))
        assertTrue(body(reopened, id, entries[4]).contains("last incomplete reply"))
        assertTrue(body(reopened, id, entries.last()).contains("503"))
        for (entry in entries) assertFalse(body(reopened, id, entry).contains("hidden-reasoning-fixture"))
        store.clear(id)
    }

    @Test fun liveCheckpointReusesSameEntryOnReplacementAndRetry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-live"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.RoundStarted(1, 1))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.TEXT, 0, "streaming now <thinking>hidden"))
        recorder.checkpoint()
        val live = SubagentDetailStore(context).history(id).single()
        assertEquals("running", live.status)
        assertEquals("streaming now ", body(store, id, live))
        recorder.onEvent(end(1, 0, "replacement"))
        recorder.onEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "STREAM_INCOMPLETE"))
        val entries = SubagentDetailStore(context).history(id)
        assertEquals(listOf("assistant", "retry"), entries.map { it.kind })
        assertEquals(live.id, entries.first().id)
        assertEquals("partial", entries.first().status)
        assertEquals("replacement", body(store, id, entries.first()))
        store.clear(id)
    }

    @Test fun transcriptFallbackCapturesUnstreamedVisibleTextWithoutReasoningOrDuplicates() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-no-stream"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.RoundStarted(1, 1))
        val messages = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "first visible",
            reasoningContent = "never save this"))
        recorder.onTranscript(messages)
        recorder.onTranscript(messages + AgentModelClient.ConversationMessage(role = "tool", content = "irrelevant"))
        recorder.onEvent(AgentEvent.RoundStarted(2, 2))
        recorder.onEvent(delta(2, AgentEvent.AssistantBlockKind.TEXT, 0, "streamed visible"))
        recorder.onTranscript(messages + AgentModelClient.ConversationMessage(role = "tool") +
            AgentModelClient.ConversationMessage(role = "assistant", content = "streamed visible", reasoningContent = "hidden"))
        recorder.finish("completed", "完成", "streamed visible")
        val entries = store.history(id)
        assertEquals(listOf("assistant", "assistant", "terminal"), entries.map { it.kind })
        assertEquals("first visible", body(store, id, entries[0]))
        assertEquals("streamed visible", body(store, id, entries[1]))
        for (entry in entries) assertFalse(body(store, id, entry).contains("hidden"))
        store.clear(id)
    }

    @Test fun historyIsPagedRedactedAndLegacyHistoryIsEmpty() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val legacy = "history-legacy"
        store.save(legacy, SubagentActivitySnapshot(subagentId = "old", detailId = legacy, prompt = "old prompt"))
        assertTrue(SubagentDetailStore(context).history(legacy).isEmpty())
        assertEquals("old prompt", store.page(legacy, "prompt", 0))

        val id = "history-pages"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.RoundStarted(1, 1))
        val visible = "安全内容😀".repeat(2_000) + "\nBearer fixturesecret\nhttps://example.invalid/path?token=fixture"
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.TEXT, 0, visible))
        recorder.onEvent(end(1, 0))
        recorder.finish("completed", "完成", visible)
        val reopened = SubagentDetailStore(context)
        val first = reopened.history(id).first()
        assertTrue(reopened.hasNext(id, "history-${first.id}", 0))
        val text = body(reopened, id, first)
        assertTrue(text.startsWith("安全内容😀"))
        assertFalse(text.contains("fixturesecret"))
        assertFalse(text.contains("token=fixture"))
        assertTrue(text.contains("<已隐藏>"))
        assertEquals(listOf("assistant", "terminal"), reopened.history(id).map { it.kind })
        store.clear(legacy); store.clear(id)
    }

    @Test fun taggedAndNativeThinkingNeverReachHistoryAndSensitiveToolIsHidden() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-hidden"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.RoundStarted(1, 1))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.THINKING, 0, "native-secret"))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.TEXT, 1, "可见<thinking>tagged-secret"))
        recorder.onEvent(end(1, 1))
        recorder.onEvent(delta(1, AgentEvent.AssistantBlockKind.TEXT, 2, "more-secret</thinking>可见结尾"))
        recorder.onEvent(end(1, 2))
        recorder.onEvent(AgentEvent.ToolStarted(1, "sensitive", "search_messages", "private-query", null))
        recorder.onToolResult(AgentModelClient.ToolCall("sensitive", "search_messages", "private-args", round = 1),
            AgentModelClient.ToolResult("private-result", sensitive = true))
        recorder.onEvent(AgentEvent.ToolFinished(1, "sensitive", "search_messages", "private-summary", 0, 0, true))
        recorder.finish("cancelled", "已停止")
        val entries = store.history(id)
        val all = buildString { for (entry in entries) append(body(store, id, entry)).append('\n') }
        assertTrue(all.contains("可见")); assertTrue(all.contains("可见结尾"))
        listOf("native-secret", "tagged-secret", "more-secret", "private-query", "private-args", "private-result", "private-summary")
            .forEach { assertFalse("leaked fixture $it", all.contains(it)) }
        assertTrue(all.contains("敏感工具结果不保存"))
        assertEquals("cancelled", entries.last().status)
        store.clear(id)
    }

    @Test fun jsonToolArgumentsAndResultsAreRedactedBeforeTheyAreCombined() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-json-secret"
        val recorder = SubagentHistoryRecorder(store, id)
        recorder.onEvent(AgentEvent.ToolStarted(1, "c", "terminal", "", null))
        recorder.onToolResult(AgentModelClient.ToolCall("c", "terminal", """{"password":"value-one"}"""),
            AgentModelClient.ToolResult("""{"api_key":"value-two","result":"visible"}"""))
        recorder.onEvent(AgentEvent.ToolFinished(1, "c", "terminal", "done", 0, 0, true))
        val text = body(store, id, store.history(id).single())
        assertFalse(text.contains("value-one")); assertFalse(text.contains("value-two"))
        assertTrue(text.contains("visible")); assertTrue(text.contains("<已隐藏>"))
        store.clear(id)
    }

    @Test fun limitsAreVisibleAndTerminalSurvivesEvenAfterEntryLimit() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-limit"
        val huge = "x".repeat(SubagentDetailStore.MAX_FIELD_CHARS + 100)
        store.recordHistory(id, SubagentHistoryEntry("large", "长文本", "assistant", "success"), huge)
        var entries = store.history(id)
        val first = body(store, id, entries.first())
        assertTrue(first.length <= SubagentDetailStore.MAX_FIELD_CHARS)
        assertTrue(first.contains("上限"))
        repeat(SubagentDetailStore.MAX_HISTORY_ENTRIES + 4) { n ->
            store.recordHistory(id, SubagentHistoryEntry("item-$n", "事件", "retry", "failed"), "事件 $n")
        }
        store.recordHistory(id, SubagentHistoryEntry("finish", "完成", "terminal", "completed"), "完成", terminal = true)
        entries = SubagentDetailStore(context).history(id)
        assertEquals(SubagentDetailStore.MAX_HISTORY_ENTRIES + 2, entries.size)
        assertEquals("limit", entries[entries.lastIndex - 1].kind)
        assertTrue(body(store, id, entries[entries.lastIndex - 1]).contains("未保存"))
        assertEquals("terminal", entries.last().kind)
        store.clear(id)
    }

    @Test fun totalBodyLimitShowsMissingContentInsteadOfSilentGrowth() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SubagentDetailStore(context)
        val id = "history-body-limit"
        repeat(6) { n ->
            store.recordHistory(id, SubagentHistoryEntry("large-$n", "长文本", "assistant", "success"), "x".repeat(120_000))
        }
        val entries = SubagentDetailStore(context).history(id)
        assertEquals(6, entries.size)
        assertTrue(body(store, id, entries.last()).contains("上限"))
        store.recordHistory(id, SubagentHistoryEntry("end", "失败", "terminal", "failed"), "关键失败原因 HTTP 503", terminal = true)
        assertTrue(body(store, id, store.history(id).last()).contains("HTTP 503"))
        var total = 0
        for (entry in entries) total += body(store, id, entry).length
        assertTrue(total <= SubagentDetailStore.MAX_HISTORY_CHARS + 256 * 32)
        store.clear(id)
    }
}
