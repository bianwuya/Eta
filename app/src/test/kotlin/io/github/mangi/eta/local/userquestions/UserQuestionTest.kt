package io.github.mangi.eta.local.userquestions

import android.content.Context
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserQuestionTest {
    private fun args() = JSONObject("""{"questions":[{"prompt":"选择模式","tab":"模式","options":[{"label":"A"},{"label":"B"}],"allow_other":true}]}""")
    @Test fun parserRejectsUnanswerableOrOversizedQuestions() {
        assertTrue(runCatching { UserQuestionParser.parse(JSONObject("{\"questions\":[]}")) }.isFailure)
        assertTrue(runCatching { UserQuestionParser.parse(JSONObject("""{"questions":[{"prompt":"空","options":[]}]}""")) }.isFailure)
        val q = UserQuestionParser.parse(args()).second.single()
        assertFalse(UserQuestionParser.valid(q, QuestionDraft()))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(setOf(0, 1))))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(setOf(0))))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(setOf(99))))
        assertFalse(UserQuestionParser.valid(q, QuestionDraft(otherSelected = true)))
        assertTrue(UserQuestionParser.valid(q, QuestionDraft(otherSelected = true, other = "本人补充")))
    }
    @Test fun answersReturnOnlyToOriginalWaiterAndSubmitExactlyOnce() {
        val context: Context = RuntimeEnvironment.getApplication()
        UserQuestionStore.initialize(context)
        val (one, first) = UserQuestionStore.open("chat-a", "run-a", "call-a", args())
        val (two, second) = UserQuestionStore.open("chat-b", "run-b", "call-b", args())
        assertFalse(UserQuestionStore.submit(one))
        UserQuestionStore.draft(one, 0, QuestionDraft(setOf(0)))
        assertTrue(UserQuestionStore.submit(one))
        assertFalse(UserQuestionStore.submit(one))
        assertTrue(JSONObject(first.get(1, TimeUnit.SECONDS)).getBoolean("ok"))
        assertFalse(second.isDone)
        UserQuestionStore.dismiss(two)
        val unanswered = JSONObject(second.get(1, TimeUnit.SECONDS))
        assertFalse(unanswered.getBoolean("ok"))
        assertEquals("USER_QUESTION_UNANSWERED", unanswered.getString("code"))
        assertFalse(UserQuestionStore.submit(two))
    }
    @Test fun repeatedCallIdReusesTheSameSetAndCancellationIsRunScoped() {
        UserQuestionStore.initialize(RuntimeEnvironment.getApplication())
        val one = UserQuestionStore.open("chat-a", "r-one", "call", args())
        val duplicate = UserQuestionStore.open("chat-a", "r-one", "call", args())
        assertEquals(one.first, duplicate.first)
        assertSame(one.second, duplicate.second)
        val two = UserQuestionStore.open("chat-b", "r-two", "call", args())
        UserQuestionStore.cancelRun("r-one")
        assertFalse(JSONObject(one.second.get(1, TimeUnit.SECONDS)).getBoolean("ok"))
        assertFalse(two.second.isDone)
        UserQuestionStore.cancelRun("r-two")
    }
}
