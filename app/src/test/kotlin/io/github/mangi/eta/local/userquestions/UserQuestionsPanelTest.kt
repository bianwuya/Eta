package io.github.mangi.eta.local.userquestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserQuestionsPanelTest {
    private val single = UserQuestion(
        prompt = "模式", tab = "模式", multiple = false, allowOther = false,
        options = listOf(QuestionOption("A"), QuestionOption("B")),
    )
    private val multiple = single.copy(prompt = "约束", tab = "约束", multiple = true)

    @Test fun selectsFirstIncompleteQuestionRatherThanCurrentOrLast() {
        assertEquals(1, firstInvalidQuestion(
            listOf(single, multiple, single),
            listOf(QuestionDraft(selected = setOf(0)), QuestionDraft(other = "  "), QuestionDraft()),
        ))
        assertEquals(0, firstInvalidQuestion(
            listOf(single, multiple), listOf(QuestionDraft(), QuestionDraft(other = "文字")),
        ))
    }

    @Test fun acceptsTextOnlyAndChoiceWithTextRegardlessOfLegacyFlags() {
        assertNull(firstInvalidQuestion(
            listOf(single, multiple, single),
            listOf(
                QuestionDraft(otherSelected = false, other = "纯文字"),
                QuestionDraft(selected = setOf(0, 1), other = "多选备注"),
                QuestionDraft(selected = setOf(1), other = "单选备注"),
            ),
        ))
    }

    @Test fun shortOrInvalidDraftSnapshotsCannotPassUiValidation() {
        assertEquals(1, firstInvalidQuestion(listOf(single, multiple), listOf(QuestionDraft(other = "已填"))))
        assertEquals(0, firstInvalidQuestion(listOf(single), listOf(QuestionDraft(selected = setOf(0, 1)))))
        assertEquals(0, firstInvalidQuestion(listOf(single), listOf(QuestionDraft(other = "字".repeat(2001)))))
    }
}
