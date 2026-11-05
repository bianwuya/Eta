package io.github.mangi.eta.local.userquestions

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserQuestionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val storage: File get() = File(context.filesDir, "userquestions")
    private val file: File get() = File(storage, "drafts.json")

    @Before fun setup() {
        UserQuestionStore.resetForTest()
        storage.deleteRecursively()
    }

    @After fun cleanup() {
        UserQuestionStore.resetForTest()
        storage.deleteRecursively()
    }

    private fun args() = JSONObject("""{"questions":[{"prompt":"选择模式","tab":"模式","options":[{"label":"A"},{"label":"B"}],"allow_other":true}]}""")
    private fun form(id: String) = UserQuestionStore.forms.value.single { it.id == id }
    private fun waitResult(future: java.util.concurrent.CompletableFuture<String>) =
        JSONObject(future.get(1, TimeUnit.SECONDS))

    @Test fun parserValidatesBoundsAndAcceptsFreeTextIndependentlyOfLegacyFlags() {
        assertTrue(runCatching { UserQuestionParser.parse(JSONObject("""{"questions":[]}""")) }.isFailure)
        assertTrue(runCatching {
            UserQuestionParser.parse(JSONObject("""{"questions":[{"prompt":"1"},{"prompt":"2"},{"prompt":"3"},{"prompt":"4"},{"prompt":"5"}]}"""))
        }.isFailure)
        assertTrue(runCatching {
            UserQuestionParser.parse(JSONObject("""{"questions":[{"prompt":"多选项","options":[{"label":"1"},{"label":"2"},{"label":"3"},{"label":"4"},{"label":"5"},{"label":"6"},{"label":"7"}]}]}"""))
        }.isFailure)
        val maximum = UserQuestionParser.parse(JSONObject("""{"questions":[{"prompt":"1","options":[{"label":"1"},{"label":"2"},{"label":"3"},{"label":"4"},{"label":"5"},{"label":"6"}]},{"prompt":"2"},{"prompt":"3"},{"prompt":"4"}]}""")).second
        assertEquals(4, maximum.size)
        assertEquals(6, maximum.first().options.size)
        val textOnly = UserQuestionParser.parse(JSONObject("""{"questions":[{"prompt":"文字","options":[],"allow_other":false}]}""")).second.single()
        assertTrue(UserQuestionParser.valid(textOnly, QuestionDraft(otherSelected = false, other = "本人补充")))
        val q = UserQuestionParser.parse(args()).second.single().copy(allowOther = false)
        assertFalse(UserQuestionParser.valid(q, QuestionDraft()))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(other = "  \n")))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(setOf(0, 1))))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(setOf(0))))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(setOf(99))))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(otherSelected = true)))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(otherSelected = true, other = "本人补充")))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(setOf(0), other = "选项加备注")))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(other = "字".repeat(2000))))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(other = "字".repeat(2001))))
        assertTrue(UserQuestionParser.valid(q.copy(multiple = true), QuestionDraft(setOf(0, 1), other = "补充")))
    }

    @Test fun toolSchemaExplainsUnconditionalFreeTextAndKeepsLegacyField() {
        val tools = org.json.JSONArray()
        AgentUserQuestionsToolCatalog.appendTo(tools)
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertTrue(function.getString("description").contains("每题始终可自由填写"))
        val properties = function.getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("questions").getJSONObject("items").getJSONObject("properties")
        assertEquals("boolean", properties.getJSONObject("allow_other").getString("type"))
        assertTrue(properties.getJSONObject("allow_other").getString("description").contains("历史兼容"))
    }

    @Test fun submitUsesLatestSnapshotForTextOnlyAndChoiceWithText() {
        UserQuestionStore.initialize(context)
        val args = JSONObject("""{"questions":[{"prompt":"纯文字","tab":"文字","allow_other":false},{"prompt":"单选带备注","tab":"模式","allow_other":false,"options":[{"label":"A"},{"label":"B"}]}]}""")
        val (id, future) = UserQuestionStore.open("chat-a", "run-a", "call-a", args)
        assertTrue(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(other = "旧值"), QuestionDraft(setOf(1)))))
        val latest = listOf(QuestionDraft(other = "纯文字"), QuestionDraft(setOf(0), otherSelected = false, other = "我的备注"))
        assertTrue(UserQuestionStore.submit(id, latest))
        assertFalse(UserQuestionStore.submit(id, latest))
        assertFalse(UserQuestionStore.saveDrafts(id, latest))
        val result = waitResult(future)
        assertTrue(result.getBoolean("ok"))
        assertEquals("answered", result.getString("status"))
        assertEquals("文字：纯文字 · 模式：A、我的备注", result.getString("summary"))
        val answers = result.getJSONArray("answers")
        assertEquals(2, answers.length())
        assertEquals("纯文字", answers.getJSONObject(0).getString("other"))
        assertEquals(0, answers.getJSONObject(0).getJSONArray("selected").length())
        assertEquals("A", answers.getJSONObject(1).getJSONArray("selected").getString(0))
        assertEquals("我的备注", answers.getJSONObject(1).getString("other"))
        assertEquals(latest, form(id).drafts)
        UserQuestionStore.resetForTest()
        UserQuestionStore.initialize(context)
        assertEquals(latest, form(id).drafts)
        assertEquals("answered", form(id).status)
    }

    @Test fun oldSubmitAndLegacyDraftStillAcceptPureSelection() {
        UserQuestionStore.initialize(context)
        val (one, first) = UserQuestionStore.open("chat-a", "run-a", "call-a", args())
        val (two, second) = UserQuestionStore.open("chat-b", "run-b", "call-b", args())
        assertFalse(UserQuestionStore.submit(one))
        UserQuestionStore.draft(one, 0, QuestionDraft(setOf(0)))
        assertTrue(UserQuestionStore.submit(one))
        assertFalse(UserQuestionStore.submit(one))
        val answer = waitResult(first).getJSONArray("answers").getJSONObject(0)
        assertEquals("A", answer.getJSONArray("selected").getString(0))
        assertEquals("", answer.getString("other"))
        assertFalse(second.isDone)
        UserQuestionStore.dismiss(two)
        assertEquals("USER_QUESTION_UNANSWERED", waitResult(second).getString("code"))
        assertFalse(UserQuestionStore.submit(two))
    }

    @Test fun presentationIsPersistentButNeverCompletesTheWaiter() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        assertEquals(UserQuestionPresentation.COLLAPSED, form(id).presentation)
        for (presentation in listOf(UserQuestionPresentation.EXPANDED, UserQuestionPresentation.COLLAPSED,
            UserQuestionPresentation.HIDDEN, UserQuestionPresentation.EXPANDED)) {
            assertTrue(UserQuestionStore.setPresentation(id, presentation))
            assertEquals(presentation, form(id).presentation)
            assertEquals("waiting", form(id).status)
            assertTrue(form(id).active)
            assertFalse(future.isDone)
        }
        UserQuestionStore.resetForTest()
        UserQuestionStore.initialize(context)
        assertEquals(UserQuestionPresentation.EXPANDED, form(id).presentation)
        assertEquals("ended", form(id).status)
        assertFalse(form(id).active)
        assertFalse(future.isDone) // old-process future is not revived or completed
        assertFalse(UserQuestionStore.setPresentation(id, UserQuestionPresentation.HIDDEN))
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(0)))))
        assertEquals("unanswered", waitResult(UserQuestionStore.open("chat", "run", "call", args()).second).getString("status"))
    }

    @Test fun saveDraftsCopiesSnapshotsAndRejectsMalformedOrLateWrites() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        val selected = mutableSetOf(0)
        val source = mutableListOf(QuestionDraft(selected, other = "原值"))
        assertTrue(UserQuestionStore.saveDrafts(id, source))
        selected.add(1)
        source[0] = QuestionDraft(other = "已变动")
        assertEquals(QuestionDraft(setOf(0), other = "原值"), form(id).drafts.single())
        assertFalse(UserQuestionStore.saveDrafts(id, emptyList()))
        assertFalse(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(setOf(2)))))
        assertFalse(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(setOf(0, 1)))))
        assertFalse(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(other = "a".repeat(2001)))))
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft())))
        assertFalse(UserQuestionStore.defer(id, emptyList()))
        assertFalse(future.isDone)
        assertTrue(UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(1), other = "点击时最新"))))
        assertFalse(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(setOf(0), other = "晚到的旧写入"))))
        assertEquals("点击时最新", waitResult(future).getJSONArray("answers").getJSONObject(0).getString("other"))
    }

    @Test fun deferPersistsLatestIncompleteDraftAndIsIdempotent() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        assertTrue(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(other = "旧值"))))
        val latest = listOf(QuestionDraft(otherSelected = false, other = "尚未写完"))
        assertTrue(UserQuestionStore.defer(id, latest))
        assertFalse(UserQuestionStore.defer(id, latest))
        assertFalse(UserQuestionStore.submit(id, latest))
        val result = waitResult(future)
        assertFalse(result.getBoolean("ok"))
        assertEquals("USER_QUESTION_UNANSWERED", result.getString("code"))
        assertEquals("unanswered", form(id).status)
        assertEquals(latest, form(id).drafts)
        UserQuestionStore.resetForTest()
        UserQuestionStore.initialize(context)
        assertEquals("unanswered", form(id).status)
        assertEquals(latest, form(id).drafts)
    }

    @Test fun failedPersistenceNeverAcknowledgesUserActionsOrCompletesFuture() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        val originalFile = file.readBytes()
        val originalForm = form(id)
        fun checkFailed(action: () -> Unit) {
            UserQuestionStore.failNextWriteForTest()
            assertTrue(runCatching(action).isFailure)
            assertEquals(originalForm, form(id))
            assertArrayEquals(originalFile, file.readBytes())
            assertFalse(future.isDone)
        }
        checkFailed { UserQuestionStore.setPresentation(id, UserQuestionPresentation.HIDDEN) }
        checkFailed { UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(other = "新草稿"))) }
        checkFailed { UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(0), other = "新提交"))) }
        checkFailed { UserQuestionStore.defer(id, listOf(QuestionDraft(other = "新稍后"))) }
        assertTrue(UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(0), other = "重试成功"))))
        assertEquals("重试成功", waitResult(future).getJSONArray("answers").getJSONObject(0).getString("other"))
    }

    @Test fun oldJsonLoadsWithCollapsedPresentationAndNoRestoredWaiter() {
        storage.mkdirs()
        file.writeText("""[{"id":"legacy","conversationId":"chat","runId":"run","callId":"call","intro":"","questions":[{"prompt":"旧题","tab":"旧","multiple":false,"allowOther":true,"options":[{"label":"A"}]}],"drafts":[{"selected":[0],"otherSelected":false,"other":"旧备注"}]}]""")
        UserQuestionStore.initialize(context)
        assertEquals(UserQuestionPresentation.COLLAPSED, form("legacy").presentation)
        assertEquals("ended", form("legacy").status)
        assertFalse(form("legacy").active)
        assertEquals("旧备注", form("legacy").drafts.single().other)
        assertFalse(UserQuestionStore.saveDrafts("legacy", listOf(QuestionDraft(setOf(0)))))
        assertFalse(UserQuestionStore.defer("legacy", listOf(QuestionDraft())))
        val replay = UserQuestionStore.open("chat", "run", "call", args())
        assertEquals("legacy", replay.first)
        assertEquals("unanswered", waitResult(replay.second).getString("status"))
    }

    @Test fun restartRecoversLegacyAtomicFileBackupWhenBaseIsMissing() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        assertTrue(UserQuestionStore.saveDrafts(id, listOf(QuestionDraft(other = "保留草稿"))))
        UserQuestionStore.resetForTest()
        val backup = File(file.path + ".bak")
        assertTrue(file.renameTo(backup))
        assertFalse(file.exists())
        UserQuestionStore.initialize(context)
        assertEquals("保留草稿", form(id).drafts.single().other)
        assertEquals("ended", form(id).status)
        assertFalse(future.isDone)
        assertTrue(file.exists())
    }

    @Test fun answeredHistoryReplaysButNeverReopensAnActiveWaiter() {
        UserQuestionStore.initialize(context)
        val args = JSONObject("""{"questions":[{"prompt":"备注","allow_other":false}]}""")
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args)
        assertTrue(UserQuestionStore.submit(id, listOf(QuestionDraft(other = "已回答"))))
        val original = waitResult(future)
        UserQuestionStore.resetForTest()
        UserQuestionStore.initialize(context)
        assertFalse(form(id).active)
        val replay = UserQuestionStore.open("chat", "run", "call", args)
        assertEquals(id, replay.first)
        assertTrue(replay.second.isDone)
        assertEquals(original.toString(), waitResult(replay.second).toString())
        assertFalse(UserQuestionStore.submit(id, listOf(QuestionDraft(other = "再次提交"))))
    }

    @Test fun repeatedCallIdReusesTheSameSetAndCancellationIsRunScoped() {
        UserQuestionStore.initialize(context)
        val one = UserQuestionStore.open("chat-a", "r-one", "call", args())
        val duplicate = UserQuestionStore.open("chat-a", "r-one", "call", args())
        assertEquals(one.first, duplicate.first)
        assertSame(one.second, duplicate.second)
        val two = UserQuestionStore.open("chat-b", "r-two", "call", args())
        UserQuestionStore.cancelRun("r-one")
        assertFalse(waitResult(one.second).getBoolean("ok"))
        assertFalse(two.second.isDone)
        UserQuestionStore.cancelRun("r-two")
    }

    @Test fun cancellationAfterSubmissionNeverRewritesItsReceipt() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        assertTrue(UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(0), other = "已提交"))))
        val before = file.readBytes()
        UserQuestionStore.dismiss(id, "原任务已停止")
        UserQuestionStore.cancelRun("run")
        assertArrayEquals(before, file.readBytes())
        assertEquals("answered", form(id).status)
        assertEquals("answered", waitResult(future).getString("status"))
    }

    @Test fun concurrentSubmitAndDeferSettleExactlyOnce() {
        UserQuestionStore.initialize(context)
        val (id, future) = UserQuestionStore.open("chat", "run", "call", args())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val submit = pool.submit<Boolean> { start.await(); UserQuestionStore.submit(id, listOf(QuestionDraft(setOf(0), other = "提交"))) }
            val defer = pool.submit<Boolean> { start.await(); UserQuestionStore.defer(id, listOf(QuestionDraft(other = "稍后"))) }
            start.countDown()
            val submitted = submit.get(2, TimeUnit.SECONDS)
            val deferred = defer.get(2, TimeUnit.SECONDS)
            assertTrue(submitted.xor(deferred))
            val result = waitResult(future)
            if (submitted) {
                assertEquals("answered", result.getString("status"))
                assertEquals("提交", form(id).drafts.single().other)
            } else {
                assertEquals("unanswered", result.getString("status"))
                assertEquals("稍后", form(id).drafts.single().other)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
