package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.local.subagentcontext.SubagentTaskGroup
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskGroupTest {
    private open class Storage : YieldableSubagents.Persistence {
        val reservations = Collections.synchronizedList(mutableListOf<String>())
        val finishes = LinkedBlockingQueue<String>()
        val raw = Collections.synchronizedMap(mutableMapOf<String, String>())
        val seals = Collections.synchronizedList(mutableListOf<String>())
        val consumed = Collections.synchronizedList(mutableListOf<String>())
        override fun reserveGroupMember(taskId: String, toolCallId: String, round: Int, prompt: String, groupKey: String) {
            reservations += taskId
        }
        override fun sealGroups(keys: Collection<String>) { seals += keys }
        override fun consumed(messageIds: Collection<String>) { consumed += messageIds }
        override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) = Unit
        override fun finished(taskId: String, report: String): Boolean { raw[taskId] = report; finishes.offer(taskId); return true }
        override fun interrupted() = Unit
    }
    private fun call(id: String, key: String? = "batch", round: Int = 1, resume: String? = null) =
        AgentModelClient.ToolCall(id, "subagent", JSONObject().put("prompt", id).put("description", id)
            .apply { if (key != null) put("group_key", key); if (resume != null) put("resume", resume) }.toString(), round)
    private fun seal(owner: YieldableSubagents, key: String = "batch") = JSONObject(owner.sealGroup(
        AgentModelClient.ToolCall("seal", "subagent_seal_group", JSONObject().put("group_key", key).toString())).content)
    private fun result(state: String = "completed", engine: String = "completed"): AgentModelClient.ToolResult {
        val semantic = SubagentTaskResult.fromFinal("task_status: $state\nsummary: summary-$state\nevidence: File:1\n" +
            "remaining: ${if (state in setOf("blocked", "partial")) "verify" else "-"}\nnext_action: -")
        return AgentModelClient.ToolResult(semantic.attach(JSONObject().put("ok", engine == "completed")
            .put("status", engine).put("message", "engine-$engine").put("full", "original-$state")).toString())
    }
    private fun awaitReport(controller: AgentRunController, owner: YieldableSubagents) {
        assertTrue(controller.awaitWorkAvailableFor(3000) { owner.decision() == YieldableSubagents.Decision.RESUME })
    }

    @Test fun sameRoundTwoMembersDeliverOnceAfterSealAndRetainFullReports() {
        val controller = AgentRunController(); val storage = Storage(); val release = CountDownLatch(1)
        val owner = YieldableSubagents(controller, storage, parentRunId = "parent") { call, started ->
            assertFalse(JSONObject(call.argumentsJson).has("group_key"))
            started("child-${call.id}", ""); check(release.await(3, TimeUnit.SECONDS)); result()
        }
        try {
            val a = JSONObject(owner.launch(call("a")).content); val b = JSONObject(owner.launch(call("b")).content)
            assertEquals(2, storage.reservations.size)
            assertEquals("batch", a.getString("group_key"))
            assertTrue(seal(owner).getBoolean("sealed")); assertFalse(controller.isCancelled)
            assertEquals(SubagentTaskGroup.State.SEALED, owner.groupState("batch"))
            release.countDown(); awaitReport(controller, owner)
            val report = owner.peekReports().single()
            assertEquals(setOf(a.getString("task_id"), b.getString("task_id")), report.members.map { it.taskId }.toSet())
            assertTrue(report.members.all { it.succeeded })
            assertEquals(SubagentTaskGroup.messageId("parent", "batch"), report.messageId)
            assertEquals(2, storage.raw.size)
            assertTrue(storage.raw.values.all { it.contains("original-completed") })
            assertFalse(report.content.contains("original-completed"))
            assertTrue(report.content.contains("不是终端用户发言或授权"))
            owner.consumed(listOf(report.taskId)); owner.consumed(listOf(report.taskId))
            assertEquals(listOf(report.messageId), storage.consumed)
            assertEquals(SubagentTaskGroup.State.DELIVERED, owner.groupState("batch"))
            assertEquals(YieldableSubagents.Decision.FINISH, owner.decision())
        } finally { release.countDown(); controller.cancel(); owner.close() }
    }

    @Test fun completedOpenAStaysPendingWithoutWakeAndLateBCanJoinAcrossRounds() {
        val controller = AgentRunController(); val storage = Storage(); val calls = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start ->
            calls.incrementAndGet(); start("same-resumed-child", ""); result()
        }
        try {
            val a = JSONObject(owner.launch(call("a", round = 1)).content).getString("task_id")
            assertNotNull(storage.finishes.poll(3, TimeUnit.SECONDS))
            assertTrue(owner.hasPending()); assertEquals(YieldableSubagents.Decision.WAIT, owner.decision())
            assertTrue(owner.peekReports().isEmpty()); assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch"))
            assertFalse("completion signal must not satisfy parent's ready predicate",
                controller.awaitWorkAvailableFor(80) { owner.decision() != YieldableSubagents.Decision.WAIT })
            val b = JSONObject(owner.launch(call("b", round = 3, resume = "same-resumed-child")).content).getString("task_id")
            assertNotEquals(a, b); assertEquals(2, calls.get())
            assertNotNull(storage.finishes.poll(3, TimeUnit.SECONDS))
            owner.sealAtNaturalWait(); awaitReport(controller, owner)
            assertEquals(setOf(a, b), owner.peekReports().single().members.map { it.taskId }.toSet())
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun allFiveSemanticStatesAndFailedCancelledInterruptedEnginesAreTerminalNotSuccessful() {
        val controller = AgentRunController(); val storage = Storage()
        val engines = listOf("failed", "cancelled", "interrupted")
        val owner = YieldableSubagents(controller, storage) { call, start ->
            start(call.id, "")
            if (call.id.startsWith("engine-")) result(engine = call.id.removePrefix("engine-")) else result(call.id)
        }
        try {
            (SubagentTaskResult.Status.entries.map { it.wire } + engines.map { "engine-$it" }).forEach { owner.launch(call(it)) }
            seal(owner); awaitReport(controller, owner)
            val report = owner.peekReports().single()
            assertEquals(8, report.members.size); assertEquals(1, report.members.count { it.succeeded })
            assertEquals(3, report.members.count { it.taskStatus == SubagentTaskResult.Status.UNKNOWN })
            assertTrue(report.content.contains("engine_status: cancelled"))
            assertTrue(report.content.contains("engine_status: interrupted"))
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun targetedCancellationExceptionSettlesButDoesNotCancelParentOrPeers() {
        val controller = AgentRunController(); val storage = Storage()
        val owner = YieldableSubagents(controller, storage) { call, start ->
            start(call.id, ""); if (call.id == "cancel") throw AgentRunCancelledException() else result()
        }
        try {
            owner.launch(call("cancel")); owner.launch(call("peer")); seal(owner); awaitReport(controller, owner)
            assertFalse(controller.isCancelled)
            assertEquals(listOf(false, true), owner.peekReports().single().members.map { it.succeeded })
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun sealedLateAddRejectedBeforeExecuteAndNewKeyStartsNextBatch() {
        val controller = AgentRunController(); val storage = Storage(); val executes = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> executes.incrementAndGet(); start("child", ""); result() }
        try {
            owner.launch(call("a")); seal(owner); assertTrue(seal(owner).getBoolean("ok"))
            val rejected = JSONObject(owner.launch(call("late", round = 5)).content)
            assertEquals("SUBAGENT_GROUP_SEALED", rejected.getString("code")); assertEquals(1, executes.get())
            owner.launch(call("next", key = "batch2")); assertEquals(2, executes.get())
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun rejectedStartupIsAVisibleTerminalMemberRatherThanDanglingWait() {
        val controller = AgentRunController(); val storage = Storage()
        val owner = YieldableSubagents(controller, storage) { _, _ ->
            AgentModelClient.ToolResult("""{"ok":false,"code":"INVALID_HANDOFF","message":"invalid"}""")
        }
        try {
            val receipt = JSONObject(owner.launch(call("invalid")).content)
            assertFalse(receipt.getBoolean("ok")); assertTrue(receipt.has("task_id"))
            seal(owner); awaitReport(controller, owner)
            val member = owner.peekReports().single().members.single()
            assertFalse(member.succeeded); assertEquals(SubagentTaskResult.Status.FAILED, member.taskStatus)
            assertTrue(storage.raw.getValue(member.taskId).contains("INVALID_HANDOFF"))
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun groupMessageIdentityUsesParentRunAndKeyNotChildSessionIdentity() {
        assertNotEquals(SubagentTaskGroup.messageId("one", "batch"), SubagentTaskGroup.messageId("two", "batch"))
        assertNotEquals(SubagentTaskGroup.messageId("one", "batch"), SubagentTaskGroup.messageId("one", "batch2"))
        assertEquals(SubagentTaskGroup.messageId("one", "batch"), SubagentTaskGroup.messageId("one", "batch"))
    }

    @Test fun ungroupedReportStillDeliversWithoutGroupSeal() {
        val controller = AgentRunController(); val storage = Storage()
        val owner = YieldableSubagents(controller, storage) { _, start -> start("legacy", ""); result() }
        try {
            val receipt = JSONObject(owner.launch(call("legacy", key = null)).content)
            assertFalse(receipt.has("group_key")); awaitReport(controller, owner)
            val report = owner.peekReports().single()
            assertEquals("subagent-report-${receipt.getString("task_id")}", report.messageId)
            assertTrue(report.content.contains("original-completed")); assertTrue(storage.seals.isEmpty())
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun reservationFailureDoesNotExecuteAndIsVisibleInsteadOfWaitingForever() {
        val controller = AgentRunController(); val executes = AtomicInteger()
        val storage = object : Storage() {
            override fun reserveGroupMember(taskId: String, toolCallId: String, round: Int, prompt: String, groupKey: String) { error("disk") }
        }
        val owner = YieldableSubagents(controller, storage) { _, _ -> executes.incrementAndGet(); result() }
        try {
            assertEquals("SUBAGENT_GROUP_STORAGE_FAILED", JSONObject(owner.launch(call("a")).content).getString("code"))
            assertEquals(0, executes.get()); assertEquals(YieldableSubagents.Decision.RESUME, owner.decision())
            assertThrows(IllegalStateException::class.java) { owner.peekReports() }
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun reportPersistenceFailureIsVisibleAndCannotMakeGroupReady() {
        val controller = AgentRunController()
        val storage = object : Storage() { override fun finished(taskId: String, report: String): Boolean = error("disk") }
        val owner = YieldableSubagents(controller, storage) { _, start -> start("child", ""); result() }
        try {
            owner.launch(call("a")); seal(owner)
            awaitReport(controller, owner)
            assertThrows(IllegalStateException::class.java) { owner.peekReports() }
            assertEquals(SubagentTaskGroup.State.SEALED, owner.groupState("batch"))
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun sealAndConsumptionStorageFailuresDoNotFalselyAcknowledgeDelivery() {
        val controller = AgentRunController()
        val storage = object : Storage() { override fun sealGroups(keys: Collection<String>) { error("disk") } }
        val owner = YieldableSubagents(controller, storage) { _, start -> start("child", ""); result() }
        try {
            owner.launch(call("a"))
            assertThrows(IllegalStateException::class.java) { seal(owner) }
            assertEquals(SubagentTaskGroup.State.OPEN, owner.groupState("batch"))
            assertThrows(IllegalStateException::class.java) { owner.peekReports() }
        } finally { controller.cancel(); owner.close() }
        val otherController = AgentRunController()
        val otherStorage = object : Storage() { override fun consumed(messageIds: Collection<String>) { error("disk") } }
        val other = YieldableSubagents(otherController, otherStorage) { _, start -> start("child", ""); result() }
        try {
            other.launch(call("a")); seal(other); awaitReport(otherController, other)
            val report = other.peekReports().single()
            assertThrows(IllegalStateException::class.java) { other.consumed(listOf(report.taskId)) }
            assertEquals(SubagentTaskGroup.State.READY, other.groupState("batch"))
            assertEquals(report, other.peekReports().single())
        } finally { otherController.cancel(); other.close() }
    }

    @Test fun optionalSchemaIsOwnerOnlyAndChildrenCannotAcquireSealPrivilege() {
        val legacy = JSONArray(); AgentSubagentToolCatalog.appendSchemas(legacy, SubagentProfile.BUILTIN_PROFILES)
        assertFalse(legacy.toString().contains("group_key")); assertFalse(legacy.toString().contains("subagent_seal_group"))
        val supported = JSONArray(); AgentSubagentToolCatalog.appendSchemas(supported, SubagentProfile.BUILTIN_PROFILES, true)
        assertTrue(supported.toString().contains("group_key")); assertEquals(5, supported.length())
        val supportedNames = (0 until supported.length()).map { supported.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        assertEquals(setOf("subagent", "subagent_profile", "subagent_control", "subagent_seal_group", "subagent_respond"), supportedNames)
        assertFalse(legacy.toString().contains("subagent_respond"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("subagent_seal_group"))
        assertFalse(SubagentProfile.CODER.isToolAllowed("subagent_respond"))
        assertTrue(SubagentProfile.CODER.isToolAllowed("ask_parent"))
        val wildcard = SubagentProfile.resolve("wildcard", SubagentProfile.BUILTIN_PROFILES, customTools = listOf("*", "subagent_seal_group", "subagent_respond"))
        assertFalse(wildcard.isToolAllowed("subagent_seal_group"))
        assertFalse(wildcard.isToolAllowed("subagent_respond"))
    }

    @Test fun boundedMemberProjectionShowsTruncationWithoutRawEnMasseCopy() {
        val semantic = SubagentTaskResult.fromFinal("task_status: completed\nsummary: ${"s".repeat(1200)}\n" +
            "evidence: ${"e".repeat(3200)}\nremaining: -\nnext_action: -")
        val members = (1..SubagentTaskGroup.MAX_MEMBERS).map { SubagentTaskGroup.Member("task-$it", "call-$it", "child-$it", semantic, true, "completed") }
        val text = SubagentTaskGroup.report("parent", "batch", members)
        assertTrue(text.length <= SubagentTaskGroup.MAX_REPORT_CHARS)
        assertEquals(SubagentTaskGroup.MAX_MEMBERS, Regex("projection_truncated: true").findAll(text).count())
        assertTrue(text.contains("full_report_ref: subagent_group_reports_v1/task-1/report"))
        assertFalse(text.contains("e".repeat(601)))
    }

    @Test fun invalidKeyAndMemberLimitRejectBeforeReservationOrExecution() {
        val controller = AgentRunController(); val storage = Storage(); val executes = AtomicInteger()
        val owner = YieldableSubagents(controller, storage) { _, start -> executes.incrementAndGet(); start("child", ""); result() }
        try {
            for (key in listOf("", "space key", "x".repeat(65)))
                assertEquals("INVALID_GROUP_KEY", JSONObject(owner.launch(call("invalid", key)).content).getString("code"))
            assertEquals(0, executes.get()); assertTrue(storage.reservations.isEmpty())
            repeat(SubagentTaskGroup.MAX_MEMBERS) { owner.launch(call("member-$it")) }
            assertEquals("SUBAGENT_GROUP_FULL", JSONObject(owner.launch(call("overflow")).content).getString("code"))
            assertEquals(SubagentTaskGroup.MAX_MEMBERS, executes.get())
            assertEquals(SubagentTaskGroup.MAX_MEMBERS, storage.reservations.size)
        } finally { controller.cancel(); owner.close() }
    }

    @Test fun abortStopsGroupedWaitingWithoutLeakingCancelledMemberAsSuccess() {
        val controller = AgentRunController(); val storage = Storage(); val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val owner = YieldableSubagents(controller, storage, cancelChildren = { release.countDown() }) { _, start ->
            start("child", ""); check(release.await(3, TimeUnit.SECONDS)); returned.countDown(); throw AgentRunCancelledException()
        }
        try {
            owner.launch(call("a")); owner.abort(); assertTrue(returned.await(3, TimeUnit.SECONDS))
            assertTrue(controller.awaitWorkAvailableFor(3000) { owner.decision() == YieldableSubagents.Decision.FINISH })
            assertTrue(owner.peekReports().isEmpty()); assertFalse(controller.isCancelled)
            assertTrue(storage.raw.isEmpty())
        } finally { release.countDown(); controller.cancel(); owner.close() }
    }
}
