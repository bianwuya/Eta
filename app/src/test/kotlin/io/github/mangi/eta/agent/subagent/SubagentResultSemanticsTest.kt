package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentResultSemanticsTest {
    private fun template(
        status: String = "completed",
        summary: String = "Implementation finished.",
        evidence: String = "Targeted checks passed.",
        remaining: String = "-",
        nextAction: String = "-",
    ): String = listOf(
        "task_status: $status",
        "summary: $summary",
        "evidence: $evidence",
        "remaining: $remaining",
        "next_action: $nextAction",
    ).joinToString("\n") { it.trimEnd() }

    private class Fixture(
        controlRegistry: SubagentControlRegistry? = null,
        onProgress: (AgentEvent.SubagentProgress) -> Unit = {},
    ) : AutoCloseable {
        val conversationKey = "result-semantics-${UUID.randomUUID()}"
        val parent = AgentRunController()
        val requests = Collections.synchronizedList(mutableListOf<ProviderRequest>())
        val toolCalls = AtomicInteger()
        var script: (Int, ProviderRequest) -> ProviderResponse = { _, _ ->
            error("No provider response scripted")
        }

        private val provider = object : AgentProviderClient {
            override val id = "result-semantics-fixture"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                val index = synchronized(requests) {
                    requests.add(request.copy(
                        messages = JSONArray(request.messages.toString()),
                        tools = JSONArray(request.tools.toString()),
                    ))
                    requests.size
                }
                return script(index, request)
            }
        }

        private val executor = SubagentExecutor(
            conversationKey = conversationKey,
            parentController = parent,
            configProvider = {
                AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid",
                    apiKey = "fixture",
                    model = "result-semantics-fixture",
                    systemPrompt = "",
                    terminalTools = true,
                    autoCompactionEnabled = false,
                )
            },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor {
                toolCalls.incrementAndGet()
                error("Result projection must not invoke a tool")
            },
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(),
            executionContext = null,
            onProgress = onProgress,
            controlRegistry = controlRegistry,
            providerFactory = { provider },
        )

        fun answer(text: String, finishReason: String = "stop"): ProviderResponse =
            ProviderResponse(JSONObject()
                .put("role", "assistant")
                .put("content", text)
                .put("finish_reason", finishReason))

        fun call(prompt: String = "Inspect the task and report the actual result.", resume: String? = null): JSONObject {
            val args = JSONObject().put("prompt", prompt).put("description", "result semantics")
            if (resume == null) {
                args.put("subagent_type", "result-semantics-fixture")
                    .put("tools", JSONArray().put("read_file"))
            } else {
                args.put("resume", resume)
            }
            val call = AgentModelClient.ToolCall(UUID.randomUUID().toString(), "subagent", args.toString())
            return JSONObject(executor.execute(1, call).content)
        }

        fun assertCalls(expected: Int) {
            assertEquals("No extra summary or repair request", expected, requests.size)
            assertTrue(requests.all { it.purpose == ProviderRequestPurpose.CHAT })
            assertEquals("No result-projection tool invocation", 0, toolCalls.get())
            requests.forEach { request ->
                val names = (0 until request.effectiveTools.length()).map {
                    request.effectiveTools.getJSONObject(it).getJSONObject("function").getString("name")
                }
                assertEquals(listOf("read_file"), names)
            }
        }

        override fun close() = parent.cancel()
    }

    private fun assertNormalResult(
        outcome: JSONObject,
        raw: String,
        status: String,
        source: String = "model",
        truncated: Boolean = false,
    ): JSONObject {
        assertTrue(outcome.getBoolean("ok"))
        assertEquals("completed", outcome.getString("status"))
        assertEquals("Full original report is the legacy summary", raw, outcome.getString("summary"))
        assertTrue(outcome.getString("subagent_id").isNotBlank())
        assertEquals(status, outcome.getString("task_status"))
        return outcome.getJSONObject("result").also { result ->
            assertEquals(1, result.getInt("version"))
            assertEquals(status, result.getString("status"))
            assertEquals(source, result.getString("source"))
            assertEquals(truncated, result.getBoolean("truncated"))
            for (field in listOf("summary", "evidence", "remaining", "next_action")) {
                assertTrue("$field must be a string", result.get(field) is String)
            }
        }
    }

    @Test fun completedTemplateKeepsEngineSuccessAndFullOriginalReport() {
        val raw = template(summary = "Completed exactly once.", evidence = "Checked the resulting file.")
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "completed")
            assertEquals("Completed exactly once.", result.getString("summary"))
            assertEquals("Checked the resulting file.", result.getString("evidence"))
            assertEquals("", result.getString("remaining"))
            assertEquals("", result.getString("next_action"))
            f.assertCalls(1)
        }
    }

    @Test fun partialTemplateDoesNotClaimSemanticCompletion() {
        val raw = template("partial", "One part is implemented.", "The first check passed.",
            "The second check is not run.", "Run the second check.")
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "partial")
            assertEquals("The second check is not run.", result.getString("remaining"))
            assertEquals("Run the second check.", result.getString("next_action"))
            f.assertCalls(1)
        }
    }

    @Test fun blockedTemplateIsEngineCompletedButTaskBlocked() {
        val raw = template("blocked", "Cannot inspect the remote artifact.", "The artifact is unavailable.",
            "Artifact verification remains blocked.", "Parent must provide the artifact.")
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "blocked")
            assertEquals("Artifact verification remains blocked.", result.getString("remaining"))
            assertEquals("Parent must provide the artifact.", result.getString("next_action"))
            f.assertCalls(1)
        }
    }

    @Test fun modelReportedFailureDoesNotBecomeAnExecutorExceptionOrSuccess() {
        val raw = template("failed", "Verification failed.", "The expected artifact is absent.",
            "The artifact still needs to be produced.", "Repair the build input.")
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "failed")
            assertEquals("Verification failed.", result.getString("summary"))
            assertEquals("The expected artifact is absent.", result.getString("evidence"))
            f.assertCalls(1)
        }
    }

    @Test fun explicitUnknownTemplateStillHasModelProvenance() {
        val raw = template("unknown", "The outcome is not verified.", "No reliable observation is available.",
            "The actual outcome is unknown.", "Inspect the output before retrying.")
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "unknown")
            assertEquals("The actual outcome is unknown.", result.getString("remaining"))
            f.assertCalls(1)
        }
    }

    @Test fun legacyProseIsUnknownEvenWhenItClaimsThatEverythingCompleted() {
        val raw = "Everything completed successfully.\nAll work is done, with no remaining tasks."
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            assertNormalResult(f.call(), raw, "unknown", source = "unknown")
            f.assertCalls(1)
        }
    }

    @Test fun missingAnyLiteralFieldIsUnknownWithoutRepairRequests() {
        val lines = template().lines()
        for (missing in lines.indices) {
            val raw = lines.filterIndexed { index, _ -> index != missing }.joinToString("\n")
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun duplicateAnyLiteralFieldIsUnknownInsteadOfChoosingOneValue() {
        val valid = template()
        for (duplicate in valid.lines()) {
            val raw = "$valid\n$duplicate"
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun outOfOrderFieldsAreUnknownEvenWhenEveryFieldIsPresent() {
        val lines = template().lines()
        for (index in 0 until lines.lastIndex) {
            val reordered = lines.toMutableList().apply { Collections.swap(this, index, index + 1) }
            val raw = reordered.joinToString("\n")
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun statusRequiresExactLiteralNameWireValueAndFirstLine() {
        val valid = template()
        val malformed = listOf(
            valid.replace("task_status:", "TASK_STATUS:"),
            valid.replace("task_status:", "task-status:"),
            template(status = "Completed"),
            template(status = "complete"),
            template(status = "completed successfully"),
            "Report follows.\n$valid",
            "```text\n$valid\n```",
            JSONObject().put("task_status", "completed").put("summary", "Done.")
                .put("evidence", "Checked.").put("remaining", "-").put("next_action", "-").toString(),
        )
        for (raw in malformed) {
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun completedRequiresNonemptySummaryEvidenceAndEmptyRemaining() {
        val invalid = listOf(
            template(summary = ""),
            template(evidence = ""),
            template(remaining = "An unchecked requirement remains."),
        )
        for (raw in invalid) {
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun partialAndBlockedRequireNonemptyRemainingAfterDashNormalization() {
        for (status in listOf("partial", "blocked")) {
            for (remaining in listOf("", "-", "   ", "  -  ")) {
                val raw = template(status = status, remaining = remaining)
                Fixture().use { f ->
                    f.script = { _, _ -> f.answer(raw) }
                    assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                    f.assertCalls(1)
                }
            }
        }
    }

    @Test fun multilineSectionsContinueUntilTheNextExactLiteralField() {
        val summary = "Implemented the change.\nThe evidence: token here is ordinary prose."
        val evidence = "First check passed.\nSecond check passed; remaining: is mentioned inline."
        val remaining = "Manual verification is pending.\nA device check is also pending."
        val nextAction = "Run the manual check.\nThen record the device result."
        val raw = template("partial", summary, evidence, remaining, nextAction)
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "partial")
            assertEquals(summary, result.getString("summary"))
            assertEquals(evidence, result.getString("evidence"))
            assertEquals(remaining, result.getString("remaining"))
            assertEquals(nextAction, result.getString("next_action"))
            f.assertCalls(1)
        }
    }

    @Test fun emptyAndDashRemainingAndNextActionNormalizeToEmptyStrings() {
        for (empty in listOf("", "-", "   ", "  -  ")) {
            val raw = template(remaining = empty, nextAction = empty)
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw) }
                val result = assertNormalResult(f.call(), raw, "completed")
                assertEquals("", result.getString("remaining"))
                assertEquals("", result.getString("next_action"))
                f.assertCalls(1)
            }
        }
    }

    @Test fun fieldsAtTheirExactCapsArePreservedWithoutATruncationFlag() {
        val summary = "S".repeat(1199) + "\uD83D\uDE80"
        val evidence = "E".repeat(3199) + "\uD83D\uDE80"
        val remaining = "R".repeat(2399) + "\uD83D\uDE80"
        val nextAction = "N".repeat(799) + "\uD83D\uDE80"
        val raw = template("partial", summary, evidence, remaining, nextAction)
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "partial")
            assertEquals(summary, result.getString("summary"))
            assertEquals(evidence, result.getString("evidence"))
            assertEquals(remaining, result.getString("remaining"))
            assertEquals(nextAction, result.getString("next_action"))
            f.assertCalls(1)
        }
    }

    @Test fun oversizedProjectionIsBoundedUnicodeSafeAndNeverTruncatesTheOriginalReport() {
        val fields = linkedMapOf(
            "summary" to ("S".repeat(1199) + "\uD83D\uDE80" + "S".repeat(130_000)),
            "evidence" to ("E".repeat(3199) + "\uD83D\uDE80" + "E".repeat(6000)),
            "remaining" to ("R".repeat(2399) + "\uD83D\uDE80" + "R".repeat(9000)),
            "next_action" to ("N".repeat(799) + "\uD83D\uDE80" + "N".repeat(4000)),
        )
        val caps = mapOf("summary" to 1200, "evidence" to 3200, "remaining" to 2400, "next_action" to 800)
        val raw = template("partial", fields.getValue("summary"), fields.getValue("evidence"),
            fields.getValue("remaining"), fields.getValue("next_action"))
        assertTrue(raw.length > 128_000)
        Fixture().use { f ->
            f.script = { _, _ -> f.answer(raw) }
            val result = assertNormalResult(f.call(), raw, "partial", truncated = true)
            for ((field, original) in fields) {
                val projected = result.getString(field)
                val cap = caps.getValue(field)
                assertEquals("$field must retain exactly its bounded prefix", cap,
                    projected.codePointCount(0, projected.length))
                assertEquals("$field must not split a surrogate or rewrite evidence",
                    original.substring(0, original.offsetByCodePoints(0, cap)), projected)
                assertTrue("$field must be projected, not retained in full", projected.length < original.length)
            }
            f.assertCalls(1)
        }
    }

    @Test fun blockedToCompletedResumeKeepsIdentityButUsesOnlyTheCurrentReport() {
        val blocked = template("blocked", "OLD_BLOCKED_SUMMARY", "OLD_BLOCKED_EVIDENCE",
            "OLD_BLOCKED_REMAINING", "Wait for the dependency.")
        val completed = template(summary = "CURRENT_COMPLETED_SUMMARY", evidence = "CURRENT_COMPLETED_EVIDENCE")
        Fixture().use { f ->
            f.script = { round, _ -> f.answer(if (round == 1) blocked else completed) }
            val first = f.call()
            assertNormalResult(first, blocked, "blocked")
            val id = first.getString("subagent_id")
            val resumed = f.call("The dependency is available; complete the remaining work.", resume = id)
            val result = assertNormalResult(resumed, completed, "completed")
            assertEquals(id, resumed.getString("subagent_id"))
            assertEquals("CURRENT_COMPLETED_SUMMARY", result.getString("summary"))
            assertEquals("CURRENT_COMPLETED_EVIDENCE", result.getString("evidence"))
            assertEquals("", result.getString("remaining"))
            assertEquals("", result.getString("next_action"))
            assertFalse(resumed.toString().contains("OLD_BLOCKED"))
            f.assertCalls(2)
            assertEquals(f.requests[0].sessionId, f.requests[1].sessionId)
            val resumedMessages = f.requests[1].messages
            assertTrue((0 until resumedMessages.length()).any {
                val message = resumedMessages.getJSONObject(it)
                message.optString("role") == "assistant" && message.optString("content") == blocked
            })
        }
    }

    @Test fun providerFailureOnResumeCannotInheritAnEarlierCompletedResult() {
        val completed = template(summary = "OLD_COMPLETED_SUMMARY", evidence = "OLD_COMPLETED_EVIDENCE")
        Fixture().use { f ->
            f.script = { round, _ ->
                if (round == 1) f.answer(completed)
                else throw AgentModelFailure("FIXTURE_PERMANENT", false, "CURRENT_PROVIDER_FAILURE")
            }
            val first = f.call()
            assertNormalResult(first, completed, "completed")
            val id = first.getString("subagent_id")
            val failed = f.call("Perform a new verification step.", resume = id)
            assertFalse(failed.getBoolean("ok"))
            assertEquals("SUBAGENT_FAILED", failed.getString("code"))
            assertEquals("failed", failed.getString("status"))
            assertEquals(id, failed.getString("subagent_id"))
            assertEquals("failed", failed.getString("task_status"))
            val result = failed.getJSONObject("result")
            assertEquals(1, result.getInt("version"))
            assertEquals("failed", result.getString("status"))
            assertEquals("runtime", result.getString("source"))
            assertFalse(result.getBoolean("truncated"))
            assertFalse(failed.optString("summary").contains("OLD_COMPLETED"))
            for (field in listOf("summary", "evidence", "remaining", "next_action")) {
                assertFalse("$field must not inherit the old report", result.getString(field).contains("OLD_COMPLETED"))
            }
            assertTrue(failed.getString("message").contains("CURRENT_PROVIDER_FAILURE"))
            f.assertCalls(2)
        }
    }

    @Test fun outputLimitMakesEvenACompleteCompletedTemplateUnknown() {
        val raw = template(summary = "Apparently complete.", evidence = "A complete-looking report.")
        for (reason in listOf("length", "max_tokens")) {
            Fixture().use { f ->
                f.script = { _, _ -> f.answer(raw, finishReason = reason) }
                assertNormalResult(f.call(), raw, "unknown", source = "unknown")
                f.assertCalls(1)
            }
        }
    }

    @Test fun providerExceptionHasRuntimeFailedSemanticsWithoutAnExtraRequest() {
        Fixture().use { f ->
            f.script = { _, _ -> throw AgentModelFailure("FIXTURE_PERMANENT", false, "CURRENT_RUNTIME_FAILURE") }
            val failed = f.call()
            assertFalse(failed.getBoolean("ok"))
            assertEquals("SUBAGENT_FAILED", failed.getString("code"))
            assertEquals("failed", failed.getString("status"))
            assertEquals("failed", failed.getString("task_status"))
            assertTrue(failed.getString("subagent_id").isNotBlank())
            val result = failed.getJSONObject("result")
            assertEquals(1, result.getInt("version"))
            assertEquals("failed", result.getString("status"))
            assertEquals("runtime", result.getString("source"))
            assertFalse(result.getBoolean("truncated"))
            for (field in listOf("summary", "evidence", "remaining", "next_action")) {
                assertTrue(result.get(field) is String)
            }
            assertTrue(failed.getString("message").contains("CURRENT_RUNTIME_FAILURE"))
            f.assertCalls(1)
        }
    }

    @Test fun acceptedCancelFromCompletedProgressCannotReturnACompletedResult() {
        val registry = SubagentControlRegistry()
        var cancelledId: String? = null
        var cancelReceipt: JSONObject? = null
        var completedProgressCount = 0
        Fixture(controlRegistry = registry, onProgress = { progress ->
            val snapshot = requireNotNull(SubagentActivitySnapshot.decode(progress.snapshotJson))
            if (snapshot.status == SubagentActivitySnapshot.STATUS_COMPLETED) {
                completedProgressCount += 1
                cancelledId = progress.subagentId
                val control = AgentModelClient.ToolCall(
                    "cancel-at-completed-progress",
                    "subagent_control",
                    JSONObject().put("action", "cancel").put("subagent_id", progress.subagentId).toString(),
                )
                cancelReceipt = JSONObject(registry.execute(control).content)
            }
        }).use { f ->
            f.script = { _, _ -> f.answer(template()) }
            var returnedResult: JSONObject? = null
            assertThrows(AgentRunCancelledException::class.java) { returnedResult = f.call() }
            assertNull("An accepted targeted cancel must not return a completed result", returnedResult)
            assertEquals(1, completedProgressCount)
            val receipt = requireNotNull(cancelReceipt)
            assertTrue(receipt.getBoolean("ok"))
            assertEquals("cancel_requested", receipt.getString("status"))
            val id = requireNotNull(cancelledId)
            assertEquals(id, receipt.getString("subagent_id"))
            val entry = requireNotNull(SubagentSessionStore.get(f.conversationKey, id))
            assertTrue(entry.explicitlyCancelled.get())
            assertFalse(entry.running.get())
            assertFalse("The targeted cancel must not cancel the parent", f.parent.isCancelled)
            f.assertCalls(1)
            val active = JSONObject(registry.execute(AgentModelClient.ToolCall(
                "list-after-targeted-cancel", "subagent_control", """{"action":"list"}""",
            )).content)
            assertEquals(0, active.getInt("count"))
        }
    }

    @Test fun acceptedCancelFromFailedProgressCannotReturnFailureOrAResumeHint() {
        val registry = SubagentControlRegistry()
        var cancelReceipt: JSONObject? = null
        var cancelledId: String? = null
        var observedCancelled = false
        Fixture(controlRegistry = registry, onProgress = { progress ->
            val snapshot = requireNotNull(SubagentActivitySnapshot.decode(progress.snapshotJson))
            if (snapshot.status == SubagentActivitySnapshot.STATUS_FAILED) {
                cancelledId = progress.subagentId
                cancelReceipt = JSONObject(registry.execute(AgentModelClient.ToolCall(
                    "cancel-failure", "subagent_control", JSONObject().put("action", "cancel")
                        .put("subagent_id", progress.subagentId).toString())).content)
            }
            if (snapshot.status == SubagentActivitySnapshot.STATUS_CANCELLED) observedCancelled = true
        }).use { f ->
            f.script = { _, _ -> throw AgentModelFailure("FIXTURE_PERMANENT", false, "failed-before-cancel") }
            var returnedResult: JSONObject? = null
            assertThrows(AgentRunCancelledException::class.java) { returnedResult = f.call() }
            assertNull("An accepted cancel must not return failed semantics or a stale resume hint", returnedResult)
            assertTrue(requireNotNull(cancelReceipt).getBoolean("ok"))
            assertTrue(observedCancelled)
            val entry = requireNotNull(SubagentSessionStore.get(f.conversationKey, requireNotNull(cancelledId)))
            assertTrue(entry.explicitlyCancelled.get())
            assertFalse(entry.running.get())
            assertFalse(f.parent.isCancelled)
            f.assertCalls(1)
        }
    }
}
