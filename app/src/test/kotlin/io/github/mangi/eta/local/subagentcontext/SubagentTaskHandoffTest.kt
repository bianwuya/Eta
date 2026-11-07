package io.github.mangi.eta.local.subagentcontext

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentTaskHandoffTest {
    private fun values(vararg items: Any): JSONArray = JSONArray(items.toList())
    private fun render(handoff: Any?, prompt: String = "inspect") = SubagentTaskHandoff.taskPrompt(prompt, handoff)
    private fun count(text: String, value: String) = text.windowed(value.length).count { it == value }
    private fun invalid(value: Any?) = assertThrows(IllegalArgumentException::class.java) { render(value) }

    @Test fun absentAndEmptyObjectsPreserveTheLegacyPromptByteForByte() {
        val prompt = "  原始指令\n  code = 1\n\n"
        for (handoff in listOf(null, JSONObject(), JSONObject().put("goal", "   "),
            JSONObject().put("known_facts", values("", " \n ")))) {
            assertEquals(prompt, render(handoff, prompt))
        }
    }

    @Test fun allFieldsKeepTheirSourceAndRequiredTextInOneTaskInput() {
        val result = render(JSONObject().put("goal", "find the real cause")
            .put("known_facts", values("already tested"))
            .put("user_constraints", values("do not modify source"))
            .put("working_hypotheses", values("maybe a missing dependency"))
            .put("investigation_leads", values("/workspace/project"))
            .put("done_when", values("report exact code lines")))
        assertTrue(result.startsWith("inspect\n\n"))
        for (text in listOf("不是新增用户授权", "已确认事实", "有效用户约束", "待验证假设（不是事实）",
            "相关调查入口（非封闭范围）", "完成标准", "find the real cause", "do not modify source")) {
            assertTrue(text, result.contains(text))
        }
    }

    @Test fun deduplicationIsExactAndOnlyWithinOneField() {
        val result = render(JSONObject().put("known_facts", values(" same ", "same", "same ", "Same", "same  text", "same text"))
            .put("working_hypotheses", values("same")).put("user_constraints", values("same")))
        assertEquals(3, result.lineSequence().count { it == "- same" })
        assertTrue(result.contains("- Same"))
        assertTrue(result.contains("- same  text"))
        assertTrue(result.contains("- same text"))
    }

    @Test fun promptGoalAndConstraintsPreserveTheirDistinctSourcesEvenWhenTextMatches() {
        val result = render(JSONObject().put("goal", " inspect ").put("user_constraints", values("inspect", "inspect")))
        assertEquals(3, count(result, "inspect"))
        assertTrue(result.contains("目标\ninspect"))
        assertTrue(result.contains("有效用户约束\n- inspect"))
    }

    @Test fun duplicateGoalDoesNotBypassTheFullRenderedBlockLimit() {
        val prompt = "x".repeat(SubagentTaskHandoff.MAX_HANDOFF_CHARS)
        assertThrows(IllegalArgumentException::class.java) {
            render(JSONObject().put("goal", prompt), prompt)
        }
    }

    @Test fun multilineCodeInternalWhitespaceAndUnicodeAreNotRewritten() {
        val code = "if (ready) {\n    保留(\"🧪\");\n}\n\n  next()"
        val result = render(JSONObject().put("known_facts", values("  $code  ")))
        assertTrue(result.contains(code))
        assertFalse(result.contains("if (ready) { 保留"))
    }

    @Test fun importantTailConstraintIsPreservedEvenAboveTheRecommendedSize() {
        val tail = "TAIL: 禁止写入或自动发送"
        val result = render(JSONObject().put("known_facts", values("x".repeat(7000)))
            .put("user_constraints", values(tail)))
        assertTrue(result.length > 6000)
        assertTrue(result.endsWith(tail))
    }

    @Test fun renderedLimitIncludesLabelsAndRejectsTheWholeTaskInsteadOfDroppingConstraints() {
        val handoff = JSONObject().put("known_facts", values("x".repeat(15900)))
            .put("user_constraints", values("critical tail guard".repeat(10)))
        val before = handoff.toString()
        val failure = invalid(handoff)
        assertTrue(failure.message.orEmpty().contains("未启动"))
        assertEquals(before, handoff.toString())
    }

    @Test fun exactRenderedBoundaryIsAcceptedAndNextCharacterRejected() {
        val initial = render(JSONObject().put("user_constraints", values("x")))
        val prefixLength = initial.removePrefix("inspect\n\n").codePointCount(0, initial.removePrefix("inspect\n\n").length) - 1
        val size = SubagentTaskHandoff.MAX_HANDOFF_CHARS - prefixLength
        val exact = render(JSONObject().put("user_constraints", values("x".repeat(size))))
        assertEquals(SubagentTaskHandoff.MAX_HANDOFF_CHARS, exact.removePrefix("inspect\n\n").length)
        invalid(JSONObject().put("user_constraints", values("x".repeat(size + 1))))
    }

    @Test fun characterBudgetMatchesJsonSchemaUnicodeCodePointsWithoutSplittingEmoji() {
        val text = "🧪".repeat(8100)
        val result = render(JSONObject().put("user_constraints", values(text)))
        assertTrue(result.endsWith(text))
        assertTrue(result.length > SubagentTaskHandoff.MAX_HANDOFF_CHARS)
        assertTrue(result.codePointCount(0, result.length) < SubagentTaskHandoff.MAX_HANDOFF_CHARS)
    }

    @Test fun legacyPromptIsNotPartOfTheNewHandoffBudget() {
        val prompt = "legacy ".repeat(5000)
        assertEquals(prompt, render(null, prompt))
        assertTrue(render(JSONObject().put("goal", "goal"), prompt).startsWith(prompt + "\n\n"))
    }

    @Test fun malformedObjectFieldsAndArrayItemsAreNeverCoercedOrSilentlyIgnored() {
        for (value in listOf(JSONObject.NULL, "string", 1, true, JSONArray(),
            JSONObject().put("unknown", "guard"), JSONObject().put("goal", 1),
            JSONObject().put("goal", JSONObject()), JSONObject().put("known_facts", "not an array"),
            JSONObject().put("user_constraints", values(JSONObject.NULL)),
            JSONObject().put("user_constraints", values(42)),
            JSONObject().put("done_when", values(JSONObject())))) invalid(value)
    }

    @Test fun explicitNullFieldsViolateTheirDeclaredTypesRatherThanDroppingConstraints() {
        for (field in listOf("goal", "known_facts", "user_constraints", "working_hypotheses", "investigation_leads", "done_when")) {
            invalid(JSONObject().put(field, JSONObject.NULL))
            // A wrong type must not make even another otherwise-valid constraint silently disappear.
            if (field != "user_constraints") invalid(JSONObject().put(field, JSONObject.NULL)
                .put("user_constraints", values("must stay intact")))
        }
    }

    @Test fun rawArrayItemLimitIsEnforcedBeforeDeduplicationAndNoValuesAreTruncated() {
        val allowed = JSONArray((1..SubagentTaskHandoff.MAX_ITEMS).map { "item-$it" })
        assertTrue(render(JSONObject().put("user_constraints", allowed)).endsWith("item-32"))
        invalid(JSONObject().put("user_constraints", JSONArray(List(33) { "same" })))
        invalid(JSONObject().put("goal", "x".repeat(SubagentTaskHandoff.MAX_HANDOFF_CHARS + 1)))
        invalid(JSONObject().put("done_when", values("x".repeat(SubagentTaskHandoff.MAX_HANDOFF_CHARS + 1))))
    }

    @Test fun deduplicationCanRemoveRedundantBackgroundWithoutLosingItsOrigin() {
        val result = render(JSONObject().put("known_facts", values("x".repeat(7000), "x".repeat(7000), "x".repeat(7000))))
        assertEquals(1, count(result, "x".repeat(7000)))
        assertTrue(result.contains("已确认事实"))
    }

    @Test fun schemaUsesMatchingLimitsOptionalFieldsAndFreshMutableInstances() {
        val schema = SubagentTaskHandoff.schema()
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertFalse(schema.has("required"))
        val properties = schema.getJSONObject("properties")
        assertEquals(6, properties.length())
        assertEquals(16000, properties.getJSONObject("goal").getInt("maxLength"))
        for (field in listOf("known_facts", "user_constraints", "working_hypotheses", "investigation_leads", "done_when")) {
            val array = properties.getJSONObject(field)
            assertEquals("array", array.getString("type"))
            assertEquals(32, array.getInt("maxItems"))
            assertEquals("string", array.getJSONObject("items").getString("type"))
            assertEquals(16000, array.getJSONObject("items").getInt("maxLength"))
        }
        properties.getJSONObject("goal").put("maxLength", 1)
        assertEquals(16000, SubagentTaskHandoff.schema().getJSONObject("properties").getJSONObject("goal").getInt("maxLength"))
    }

    @Test fun parallelRenderingDoesNotShareTaskOrSchemaMutation() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..20).map { id -> pool.submit(Callable {
                val marker = "task-$id-end"
                val schema = SubagentTaskHandoff.schema()
                schema.getJSONObject("properties").put("probe", JSONObject())
                val result = render(JSONObject().put("goal", marker).put("user_constraints", values("guard-$id-end")))
                assertTrue(result.contains(marker))
                assertFalse(SubagentTaskHandoff.schema().getJSONObject("properties").has("probe"))
                result
            }) }
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(20, results.distinct().size)
        } finally { pool.shutdownNow() }
    }
}
