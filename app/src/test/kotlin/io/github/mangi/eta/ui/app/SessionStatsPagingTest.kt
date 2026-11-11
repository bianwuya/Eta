package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionStatsPagingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun prefixMatchesAllStoredUsageSemanticsWithoutReadingOldText() = runBlocking {
        val usages = listOf(null, TokenUsageUi(inputTokens = 100, cachedTokens = 20, outputTokens = 10),
            TokenUsageUi(inputTokens = 10, cachedTokens = 30, outputTokens = 8, reasoningTokens = 3),
            TokenUsageUi(contextTokens = 90, outputTokens = 10, stepCount = 0, llmMs = 30, toolMs = 4,
                ttftMs = 5, ttftSteps = 2, decodeMs = 20, decodeTokens = 7),
            TokenUsageUi(reasoningTokens = 4), TokenUsageUi(inputTokens = 0, cachedTokens = 0, outputTokens = 0))
        val messages = (0 until 200).flatMap { i -> listOf(UserMessageUi("u-$i", "question"),
            AgentMessageUi("a-$i", "answer".repeat(2000), usage = usages[i % usages.size])) }
        val state = state(messages)
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(state))
        val recent = AgentConversationStore.loadConversation(context, "source")!!
        val expected = latestContextUsage(messages, null).sessionStats
        assertEquals(expected, latestContextUsage(recent.messages, null, recent.sessionStatsPrefix).sessionStats)
        // Missing offscreen text proves the prefix query never restores message bodies.
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_text_chunks WHERE owner_table = 'conversation_messages' AND owner_id = 'a-0'")
        val reloaded = AgentConversationStore.loadConversation(context, "source")!!
        assertEquals(expected, latestContextUsage(reloaded.messages, null, reloaded.sessionStatsPrefix).sessionStats)
    }

    @Test fun replacingLiveUsageAndRegeneratingDoNotDoubleCountTheStoredSuffix() = runBlocking {
        val messages = (0 until 100).flatMap { i -> listOf(UserMessageUi("u-$i", "question"),
            AgentMessageUi("a-$i", "answer", usage = TokenUsageUi(inputTokens = 100, outputTokens = 10))) }
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(state(messages)))
        val loaded = AgentConversationStore.loadConversation(context, "source")!!
        persistence.adoptConversation("source", loaded, "source", 1L)
        val last = loaded.messages.last() as AgentMessageUi
        val updated = loaded.copy(messages = loaded.messages.dropLast(1) + last.copy(usage = TokenUsageUi(inputTokens = 250, outputTokens = 20)))
        fun stats(s: AgentChatHomeUiState) = latestContextUsage(s.messages, null, s.sessionStatsPrefix).sessionStats
        assertEquals(10150, stats(updated).promptTokens)
        assertEquals(100, stats(updated).turns)
        persistence.save(context, snapshot(updated))
        assertEquals(stats(updated), stats(AgentConversationStore.loadConversation(context, "source")!!))
        val regenerated = updated.copy(messages = updated.messages.dropLast(1))
        assertEquals(99, stats(regenerated).turns)
        assertEquals(9900, stats(regenerated).promptTokens)
        persistence.save(context, snapshot(regenerated))
        assertEquals(stats(regenerated), stats(AgentConversationStore.loadConversation(context, "source")!!))
        val replacement = regenerated.copy(messages = regenerated.messages + AgentMessageUi("replacement", "new", usage = TokenUsageUi(inputTokens = 150)))
        assertEquals(10050, stats(replacement).promptTokens)
        assertEquals(100, stats(replacement).turns)
    }

    @Test fun branchReloadsItsOwnTruncatedPrefix() = runBlocking {
        val messages = (0 until 160).flatMap { i -> listOf(UserMessageUi("u-$i", "question"),
            AgentMessageUi("a-$i-0", "answer", usage = TokenUsageUi(inputTokens = 100))) }
        val journal = (0 until 160).flatMap { i -> listOf(ConversationMessage("user", "question", messageId = "u-$i"),
            ConversationMessage("assistant", "answer", messageId = "a-$i")) }
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(state(messages).copy(history = journal, journal = journal)))
        val child = persistence.branch(context, "source", "a-99-0", "child", "branch", 2L)
        assertEquals(128, child.messages.size)
        val stats = latestContextUsage(child.messages, null, child.sessionStatsPrefix).sessionStats
        assertEquals(100, stats.turns)
        assertEquals(10000, stats.promptTokens)
        assertEquals(36, child.sessionStatsPrefix.turns)
    }

    @Test fun derivedPrefixNeverEntersPersistenceComparison() {
        val source = state(emptyList())
        val changed = source.copy(sessionStatsPrefix = SessionStatsSummaryUi(turns = 99, promptTokens = 123))
        assertEquals(AgentConversationPersistence.Content(source, "title", 1L),
            AgentConversationPersistence.Content(changed, "title", 1L))
    }

    private fun state(messages: List<AgentChatMessageUi>) = AgentChatHomeUiState(
        messages = messages, input = "", isStreaming = false, thinkingEnabled = false)
    private fun snapshot(state: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
        "source", mapOf("source" to state), mapOf("source" to "source"), mapOf("source" to 1L))
}
