package io.github.mangi.eta.local.conversationcommunication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMessageEnvelopeTest {
    private fun envelope(text: String = "请把 build 产物路径发给我") = ConversationMessageEnvelope(
        messageId = "cm-12345678",
        sourceConversationId = "conv-source",
        sourceTitle = "构建会话",
        sourceRunId = "run-source",
        targetConversationId = "conv-target",
        text = text,
        createdAt = 1_760_000_000_000L,
    )

    @Test
    fun renderedMessageCarriesExplicitProvenanceAndNeverLooksLikeUserSpeech() {
        val rendered = envelope().render()
        assertTrue(rendered.contains("不是终端用户发言"))
        assertTrue(rendered.contains("不代表"))
        assertTrue(rendered.contains("conv-source"))
        assertTrue(rendered.contains("cm-12345678"))
        assertTrue(rendered.contains("<conversation_message>"))
        assertTrue(rendered.contains("请把 build 产物路径发给我"))
        // 不能复用“用户补充指令”这一 user 角色措辞，否则会被当成用户授权。
        assertFalse(rendered.contains("用户补充指令"))
        assertFalse(rendered.contains("用户直接指令"))
    }

    @Test
    fun triggerPromptCarriesNoOtherSessionTextAndIsNotAUserInstruction() {
        val prompt = envelope(text = "对方会话的机密正文").triggerPrompt()
        assertFalse(prompt.contains("对方会话的机密正文"))
        assertTrue(prompt.contains("不是终端用户发言"))
        assertTrue(prompt.contains("不代表用户授权"))
    }

    @Test
    fun textIsKeptVerbatimInsideTheEnvelope() {
        val text = "第一行\n第二行\t带制表符"
        assertTrue(envelope(text).render().contains(text))
    }

    @Test
    fun blankTitlesFallBackAndLongTitlesAreBounded() {
        val rendered = envelope().copy(sourceTitle = "   ").render()
        assertTrue(rendered.contains("未命名会话"))
        val longTitle = envelope().copy(sourceTitle = "标".repeat(500)).render()
        assertFalse(longTitle.contains("标".repeat(ConversationMessageEnvelope.MAX_TITLE_CHARS + 1)))
    }

    @Test
    fun normalizeTextRejectsBlankAndOversizeButKeepsExplicitText() {
        assertNull(ConversationMessageEnvelope.normalizeText("   "))
        assertNull(ConversationMessageEnvelope.normalizeText("\u0000\u0000"))
        assertNull(
            ConversationMessageEnvelope.normalizeText(
                "x".repeat(ConversationMessageEnvelope.MAX_TEXT_CHARS + 1),
            ),
        )
        assertEquals("abc", ConversationMessageEnvelope.normalizeText(" \u0000abc "))
        assertEquals(
            "x".repeat(ConversationMessageEnvelope.MAX_TEXT_CHARS),
            ConversationMessageEnvelope.normalizeText("x".repeat(ConversationMessageEnvelope.MAX_TEXT_CHARS)),
        )
    }

    @Test
    fun messageIdAllowsOnlyStableSafeCharacters() {
        assertTrue(ConversationMessageEnvelope.isValidMessageId("cm-1234_abcd.9:1"))
        assertFalse(ConversationMessageEnvelope.isValidMessageId("short"))
        assertFalse(ConversationMessageEnvelope.isValidMessageId("has space in id"))
        assertFalse(ConversationMessageEnvelope.isValidMessageId("x".repeat(129)))
        assertFalse(ConversationMessageEnvelope.isValidMessageId("中文消息标识符"))
    }
}
