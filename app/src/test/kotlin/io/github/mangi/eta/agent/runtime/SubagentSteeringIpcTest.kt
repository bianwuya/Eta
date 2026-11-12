package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.subagent.*
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SubagentSteeringIpcTest {
    @Before fun reset() { EtaDatabase.closeForTests(); RuntimeEnvironment.getApplication().deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }
    private fun provider(script: (ProviderRequest, AgentRunController) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = script(request, runController)
    }
    private fun answer(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))

    @Test fun realMessengerSteeringReachesParentBeforeOriginalChildRequestCompletes() {
        RuntimeMessengerFixture().use { fixture ->
            val request = fixture.request("child-steering")
            val run = fixture.admit(request)
            run.prepared(request)
            val childStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val awakened = CountDownLatch(1)
            val parentCalls = AtomicInteger()
            val childCalls = AtomicInteger()
            var childController: AgentRunController? = null
            val child = provider { _, controller ->
                childController = controller
                childCalls.incrementAndGet(); childStarted.countDown()
                val cancellation = controller.register { release.countDown() }
                try { check(release.await(10, TimeUnit.SECONDS)); controller.throwIfCancelled(); answer("original child result") }
                finally { cancellation.close() }
            }
            val control = SubagentControlRegistry()
            SubagentSessionStore.initialize(fixture.context)
            val executor = SubagentExecutor("steering-${System.nanoTime()}", run.session.controller,
                { request.config }, { AgentToolCapabilities(rootAvailable = false) }, { error("no file tool expected") },
                SkillContext.EMPTY, AgentMemoryContext.DISABLED, JSONArray(), null, {},
                providerFactory = { child }, controlRegistry = control)
            val parent = provider { modelRequest, _ ->
                when (parentCalls.incrementAndGet()) {
                    1 -> ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(JSONObject().put("id", "child").put("type", "function")
                            .put("function", JSONObject().put("name", "subagent").put("arguments", """{"prompt":"inspect","description":"inspect"}""")))))
                    2 -> { assertTrue(modelRequest.messages.toString().contains("new user guidance")); awakened.countDown(); answer("parent awake") }
                    else -> answer("done")
                }
            }
            val result = fixture.worker {
                AgentModelClient.complete(request.config, request.prompt, { call -> executor.execute(call.round, call) },
                    provider = parent, runController = run.session.controller,
                    additionalTools = JSONArray().put(AgentSubagentToolCatalog.schema()),
                    onTranscript = { run.session.updateTranscript(it) })
            }
            try {
                fixture.await { childStarted.count == 0L }
                assertEquals(AgentRuntimeClient.SteeringOutcome.Accepted,
                    fixture.result(fixture.worker { fixture.client.steerRun(run.runId, "steer-child", "new user guidance") }))
                fixture.await { awakened.count == 0L }
                assertEquals(1L, release.count)
                assertFalse(requireNotNull(childController).isCancelled)
                assertFalse(result.isDone)
                release.countDown()
                val completed = fixture.result(result)
                assertEquals("done", completed.content)
                assertEquals(1, childCalls.get())
                assertEquals(1, completed.transcript.count { it.messageId.startsWith("subagent-result-") })
            } finally { release.countDown(); run.session.controller.cancel() }
        }
    }
}
