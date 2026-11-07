package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.subagentcontext.MailboxMemoryStorage
import io.github.mangi.eta.local.subagentcontext.SubagentMailbox
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentMailboxRecoveryRegressionTest {
    private fun call(id: String, name: String, args: JSONObject) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tools(call: JSONObject) = ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call)))
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun result(request: ProviderRequest, id: String) = (0 until request.messages.length()).map(request.messages::getJSONObject)
        .single { it.optString("role") == "tool" && it.optString("tool_call_id") == id }

    @Test fun compactionKeepsCurrentInboxOnlyAndResumeCannotReadAnotherOldMessage() {
        val parent = AgentRunController(); val store = MailboxMemoryStorage(); val sender = store.add("a")
        val conversation = "combined-mailbox-${UUID.randomUUID()}"
        val entry = SubagentSessionStore.create(conversation, SubagentProfile.CODER.copy(explicitTools = setOf("agent_mailbox", "read_file")))
        SubagentSessionStore.complete(conversation, entry.subagentId, listOf(
            AgentModelClient.ConversationMessage("user", "OLD_USER".repeat(5000)),
            AgentModelClient.ConversationMessage("assistant", "OLD_ASSISTANT".repeat(5000))))
        val chats = AtomicInteger(); val compactions = AtomicInteger(); val ordinary = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val sessions = mutableListOf<String>(); val systems = mutableListOf<String>(); val toolsSeen = mutableListOf<String>()
        val model = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                try {
                    val text = request.messages.toString()
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        assertEquals(1_000_000, request.config.contextWindow)
                        if (chats.get() < 3) assertFalse(text.contains("SECRET_READ"))
                        assertFalse(text.contains("SECRET_OLD"))
                        compactions.incrementAndGet(); return answer("retained context")
                    }
                    sessions.add(request.sessionId); toolsSeen.add(request.tools.toString())
                    assertEquals(128_000, request.config.contextWindow)
                    // Save the actual system-bearing provider projection, not a guessed role for runtime reports.
                    systems.add((0 until request.messages.length()).map(request.messages::getJSONObject)
                        .filter { it.optString("role") == "system" }.joinToString { it.toString() })
                    return when (chats.incrementAndGet()) {
                        1 -> {
                            assertFalse(text.contains("SECRET_READ")); assertFalse(text.contains("SECRET_OLD"))
                            onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120_000)))
                            tools(call("once", "read_file", JSONObject().put("path", "/fixture")))
                        }
                        2 -> {
                            assertTrue(compactions.get() >= 1); assertFalse(text.contains("SECRET_READ")); assertFalse(text.contains("SECRET_OLD"))
                            assertEquals("STORED", store.messages.getJSONObject(0).getString("status"))
                            tools(call("real-read", "agent_mailbox", JSONObject().put("action", "read").put("message_id", "read-me")))
                        }
                        3 -> {
                            val matching = (0 until request.messages.length()).map(request.messages::getJSONObject)
                                .filter { it.optString("role") == "tool" && it.optString("tool_call_id") == "real-read" }
                            assertEquals(1, matching.size)
                            assertEquals("SECRET_READ", JSONObject(matching.single().getString("content")).getString("content"))
                            assertFalse(text.contains("SECRET_OLD")); answer("FIRST_COMPLETE")
                        }
                        4 -> {
                            assertTrue(text.contains("FIRST_COMPLETE")); assertFalse(text.contains("SECRET_OLD"))
                            tools(call("new-read", "agent_mailbox", JSONObject().put("action", "read").put("message_id", "old-unread")))
                        }
                        5 -> {
                            assertEquals("MAILBOX_MESSAGE_UNAVAILABLE", JSONObject(result(request, "new-read").getString("content")).getString("code"))
                            assertFalse(text.contains("SECRET_OLD")); answer("RESUMED_COMPLETE")
                        }
                        else -> error("unexpected extra provider round")
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t); throw t }
            }
        }
        val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid", apiKey = "fixture",
            model = "gpt-fixture", systemPrompt = "stable-parent", terminalTools = true, contextWindow = 128_000, autoCompactionEnabled = true)
        val worker = SubagentExecutor(conversationKey = conversation, parentController = parent, parentRunId = "parent",
            configProvider = { config }, capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            compactionTarget = { child -> AgentCompactionTarget(child.copy(contextWindow = 1_000_000), model) },
            baseToolExecutor = AgentModelClient.ToolExecutor { ordinary.incrementAndGet(); AgentModelClient.ToolResult("once") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {}, mailboxStorage = store, providerFactory = { model })
        fun send(message: String, body: String) {
            val response = SubagentMailbox(sender, store) {}.execute(AgentModelClient.ToolCall("send-$message", "agent_mailbox", JSONObject()
                .put("action", "send").put("receiver", "first").put("message_id", message).put("content", body).toString()))
            assertEquals("STORED", JSONObject(response.content).getJSONObject("receipt").getString("status"))
        }
        fun invoke(task: String): JSONObject {
            store.reserveGroupMember(task, "launch-$task", 1, "fixture", "batch")
            val args = JSONObject().put("prompt", "CURRENT_TASK").put("description", "fixture").put("resume", entry.subagentId)
            return JSONObject(worker.execute(1, AgentModelClient.ToolCall("launch-$task", "subagent", args.toString()),
                SubagentMailbox.Invocation(SubagentMailbox.Scope("parent", "batch"), task)) { id, _ ->
                store.started(task, "launch-$task", 1, id, "fixture", "accepted")
                if (task == "first") { send("read-me", "SECRET_READ"); send("old-unread", "SECRET_OLD") }
            }.content)
        }
        try {
            val first = invoke("first"); failure.get()?.let { throw AssertionError("provider callback", it) }
            assertTrue(first.getBoolean("ok")); assertEquals("READ", store.messages.getJSONObject(0).getString("status"))
            assertEquals("STORED", store.messages.getJSONObject(1).getString("status"))
            val resumed = invoke("second"); failure.get()?.let { throw AssertionError("provider callback", it) }
            assertTrue(resumed.getBoolean("ok")); assertEquals(first.getString("subagent_id"), resumed.getString("subagent_id"))
            assertEquals("STORED", store.messages.getJSONObject(1).getString("status"))
            assertEquals(5, chats.get()); assertTrue(compactions.get() >= 1); assertEquals(1, ordinary.get())
            assertEquals(1, sessions.distinct().size); assertEquals(1, toolsSeen.distinct().size); assertEquals(1, systems.distinct().size)
        } finally { parent.cancel() }
    }
}
