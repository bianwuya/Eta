package io.github.mangi.eta.ui.app

import android.os.Looper
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class ManualConversationTitleTest {
    @Test fun manualGenerationReadsOriginalTurnOutsideCurrentMessagePage() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).declareActionUnbindable(io.github.mangi.eta.agent.runtime.AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        val messages = (0 until 100).flatMap { i -> listOf(
            UserMessageUi("u-$i", "original question $i"), AgentMessageUi("a-$i", "original answer $i")) }
        runBlocking { AgentConversationStore.save(context, "paged", mapOf("paged" to AgentChatHomeUiState(
            messages = messages, input = "", isStreaming = false, thinkingEnabled = false)),
            mapOf("paged" to "old title"), mapOf("paged" to 1L)) }
        val requests = CopyOnWriteArrayList<ProviderRequest>()
        var manualRequested = false
        val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
            model = "title", systemPrompt = "")
        val generator = ConversationTitleGenerator(loadConfig = { _, manual -> manualRequested = manual; config },
            providerFor = { object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = OpenAiChatCompletionsProvider.capabilities
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    requests += request
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "new title").put("finish_reason", "stop"))
                }
            } })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope, titleGenerator = generator)
            assertTrue(state.homeState.hasEarlierMessages)
            state.regenerateConversationTitle("paged")
            val deadline = System.nanoTime() + 10_000_000_000L
            while (state.currentConversationTitle != "new title" && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertEquals("new title", state.currentConversationTitle)
            assertTrue(manualRequested)
            val payload = requests.single().messages.toString()
            assertTrue(payload.contains("original question 0"))
            assertTrue(payload.contains("original answer 0"))
            assertFalse(payload.contains("original question 99"))
        } finally {
            scope.cancel(); shadowOf(Looper.getMainLooper()).idle(); EtaDatabase.closeForTests()
        }
    }
}
