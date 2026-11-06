package io.github.mangi.eta.local.subagentcontext

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentProviderProjectionTest {
    private val config=AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="fixture",model="fixture",systemPrompt="child role")
    private fun messages()=AgentPromptBuilder.buildInitialMessages(config,
        SubagentTaskHandoff.taskPrompt("inspect",JSONObject().put("goal","DYNAMIC_TASK_SENTINEL")),emptyList(),emptyList(),
        SkillContext.EMPTY,AgentMemoryContext.DISABLED,runRole=AgentRunRole.SUBAGENT)
    private fun tools()=JSONArray().put(AgentSubagentToolCatalog.schema(SubagentProfile.BUILTIN_PROFILES))
    private fun checkSchema(parameters: JSONObject) {
        assertTrue(parameters.getJSONObject("properties").has("handoff"))
        val required=parameters.getJSONArray("required")
        assertEquals(listOf("prompt","description"),(0 until required.length()).map(required::getString))
        assertFalse(parameters.getJSONObject("properties").getJSONObject("handoff").has("required"))
    }

    @Test fun openAiChatKeepsHandoffInUserInputAndOptionalSchemaIntact() {
        val request=OpenAiChatCompletionsProvider.buildRequestJson(config,messages(),tools())
        val wire=request.getJSONArray("messages")
        assertTrue(wire.getJSONObject(wire.length()-1).getString("content").contains("DYNAMIC_TASK_SENTINEL"))
        assertEquals("user",wire.getJSONObject(wire.length()-1).getString("role"))
        assertFalse(wire.getJSONObject(0).getString("content").contains("DYNAMIC_TASK_SENTINEL"))
        checkSchema(request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters"))
    }

    @Test fun responsesUsesInstructionsForSystemButNotDynamicTask() {
        val request=ResponsesRequestBuilder.build(config,messages(),tools())
        assertFalse(request.getString("instructions").contains("DYNAMIC_TASK_SENTINEL"))
        assertTrue(request.getJSONArray("input").toString().contains("DYNAMIC_TASK_SENTINEL"))
        checkSchema(request.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters"))
        assertFalse(request.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
    }

    @Test fun geminiRetainsOptionalNestedSchemaWithoutPromotingTaskIntoSystemInstruction() {
        val request=GeminiInteractionsRequest.build(config.copy(providerType=ProviderTypes.GEMINI,reasoningEffort=io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT),messages(),tools())
        assertFalse(request.getString("system_instruction").contains("DYNAMIC_TASK_SENTINEL"))
        assertTrue(request.getJSONArray("input").toString().contains("DYNAMIC_TASK_SENTINEL"))
        checkSchema(request.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters"))
    }

    @Test fun anthropicUsesTheExistingTransportAndKeepsTaskOutOfSystemBlocks() {
        val captured=AtomicReference<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/messages") { exchange ->
            captured.set(exchange.requestBody.bufferedReader().readText())
            val body="event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type","text/event-stream")
            exchange.sendResponseHeaders(200,body.size.toLong())
            exchange.responseBody.use { it.write(body) };exchange.close()
        }
        server.start()
        try {
            val local=config.copy(baseUrl="http://127.0.0.1:${server.address.port}",providerType=ProviderTypes.ANTHROPIC)
            AnthropicMessagesProvider.complete(ProviderRequest(local,messages(),tools()),AgentRunController())
            val request=JSONObject(captured.get())
            assertFalse(request.getJSONArray("system").toString().contains("DYNAMIC_TASK_SENTINEL"))
            assertTrue(request.getJSONArray("messages").toString().contains("DYNAMIC_TASK_SENTINEL"))
            checkSchema(request.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema"))
        } finally {server.stop(0)}
    }
}
