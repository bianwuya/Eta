package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SubagentRunningOwnershipIntegrationTest {
    @Test fun failurePublicationExceptionKeepsClaimThroughCleanup() = exercise(configFailure = false)
    @Test fun configFailureKeepsClaimThroughProgressAndUnwind() = exercise(configFailure = true)

    private fun exercise(configFailure: Boolean) {
        val key = "ownership-${UUID.randomUUID()}"
        val parent = AgentRunController()
        val registry = SubagentControlRegistry()
        val oldBlocked = CountDownLatch(1); val releaseOld = CountDownLatch(1)
        val newBlocked = CountDownLatch(1); val releaseNew = CountDownLatch(1)
        val childId = AtomicReference<String>()
        val unexpected = AtomicReference<Throwable?>()
        val configs = AtomicInteger(); val models = AtomicInteger(); val factories = AtomicInteger()
        val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://example.invalid",
            apiKey = "fixture", model = "fixture", systemPrompt = "", terminalTools = true, autoCompactionEnabled = false)
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = try {
                val n = models.incrementAndGet()
                if (!configFailure && n == 1) throw AgentModelFailure("FIXTURE", false, "provider failed")
                newBlocked.countDown()
                check(releaseNew.await(5, TimeUnit.SECONDS)) { "new provider timed out" }
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "new response").put("finish_reason", "stop"))
            } catch (t: Throwable) {
                if (t !is AgentModelFailure) unexpected.compareAndSet(null, t)
                throw t
            }
        }
        val worker = SubagentExecutor(conversationKey = key, parentController = parent,
            configProvider = { if (configFailure && configs.incrementAndGet() == 1) error("config fixture") else config },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("unexpected tool") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(), executionContext = null, controlRegistry = registry,
            childToolFactory = {
                val old = !configFailure && factories.incrementAndGet() == 1
                object : AgentModelClient.ToolExecutor, AutoCloseable {
                    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult = error("unexpected tool")
                    override fun close() {
                        if (old) try {
                            oldBlocked.countDown()
                            check(releaseOld.await(5, TimeUnit.SECONDS)) { "old cleanup timed out" }
                        } catch (t: Throwable) { unexpected.compareAndSet(null, t); throw t }
                    }
                }
            },
            onProgress = { event ->
                try {
                    if (event.toolCallId == "old") {
                        childId.set(event.subagentId)
                        val status = JSONObject(event.snapshotJson).optString("status")
                        if (configFailure) {
                            oldBlocked.countDown()
                            check(releaseOld.await(5, TimeUnit.SECONDS)) { "old progress timed out" }
                            throw IllegalStateException("expected config publication failure")
                        } else if (status == SubagentActivitySnapshot.STATUS_FAILED) {
                            // Force the actual failure-publication finish to throw: the registry
                            // has already selected its result. Its subsequent cleanup is held open.
                            registry.finish(event.subagentId, "old", AgentModelClient.ToolResult("{}"))
                        }
                    }
                } catch (t: Throwable) {
                    if (!(configFailure && t is IllegalStateException && t.message == "expected config publication failure"))
                        unexpected.compareAndSet(null, t)
                    throw t
                }
            }, providerFactory = { provider })
        fun call(id: String, resume: String? = null) = AgentModelClient.ToolCall(id, "subagent", JSONObject()
            .put("prompt", "task-$id").put("description", "fixture")
            .also { if (resume != null) it.put("resume", resume) }.toString())
        fun code(result: AgentModelClient.ToolResult) = JSONObject(result.content).optString("code")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val old = pool.submit(Callable { worker.execute(1, call("old")) })
            assertTrue("old invocation did not block", oldBlocked.await(4, TimeUnit.SECONDS))
            val id = checkNotNull(childId.get())
            val entry = checkNotNull(SubagentSessionStore.get(key, id))
            assertTrue(entry.running.get())
            assertEquals("SUBAGENT_ALREADY_RUNNING", code(worker.execute(1, call("overlap", id))))
            val oldHistory = entry.history.toList()
            val oldContext = entry.lastContext
            releaseOld.countDown()
            val result = old.get(4, TimeUnit.SECONDS)
            assertEquals("SUBAGENT_START_FAILED", code(result))
            val message = JSONObject(result.content).optString("message")
            if (configFailure) assertEquals("expected config publication failure", message)
            else assertTrue("failure publication must report its actual exception", message.isNotBlank())
            assertFalse(entry.running.get())
            assertEquals(oldHistory, entry.history)
            assertEquals(oldContext, entry.lastContext)
            val resumed = pool.submit(Callable { worker.execute(1, call("new", id)) })
            assertTrue("new invocation did not reach provider", newBlocked.await(4, TimeUnit.SECONDS))
            assertTrue(entry.running.get())
            val newHistory = entry.history.toList(); val newContext = entry.lastContext
            assertEquals("SUBAGENT_ALREADY_RUNNING", code(worker.execute(1, call("third", id))))
            assertTrue(entry.running.get())
            assertEquals(newHistory, entry.history); assertEquals(newContext, entry.lastContext)
            releaseNew.countDown()
            val resumedResult = resumed.get(4, TimeUnit.SECONDS)
            assertTrue(resumedResult.content, JSONObject(resumedResult.content).getBoolean("ok"))
            assertFalse(entry.running.get())
            assertTrue(entry.history.any { it.content.contains("new response") })
            unexpected.get()?.let { throw AssertionError("unexpected callback failure", it) }
        } finally {
            releaseOld.countDown(); releaseNew.countDown(); pool.shutdownNow()
            unexpected.get()?.let { throw AssertionError("unexpected callback failure", it) }
        }
    }
}
