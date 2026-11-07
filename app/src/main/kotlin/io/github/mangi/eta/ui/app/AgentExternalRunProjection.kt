package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** The actual input is keyed by the original run, including a late deferred ingestion. */
internal object AgentExternalRunProjection {
    fun withInput(
        state: AgentChatHomeUiState,
        runId: String,
        text: String,
        images: List<String>,
    ): AgentChatHomeUiState {
        if (text.isBlank() && images.isEmpty()) return state
        val previous = state.messages.filterIsInstance<UserMessageUi>().firstOrNull { it.id == "user-$runId" }
        val user = UserMessageUi("user-$runId", text.ifBlank { previous?.content.orEmpty() },
            images.ifEmpty { previous?.images.orEmpty() })
        val index = state.messages.indexOfFirst { it.id == user.id }
        val messages = if (index >= 0) state.messages.map { if (it.id == user.id) user else it }
            else state.messages.toMutableList().also { messages ->
                val trace = messages.indexOfFirst {
                    it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-") ||
                        it.id.startsWith("$runId-thinking-") || it.id.startsWith("$runId-tool-")
                }
                messages.add(if (trace >= 0) trace else messages.size, user)
            }
        val historyUser = AgentModelClient.buildUserHistoryMessage(user.content, user.images.map { reference ->
            AgentModelClient.ModelImage(reference,
                reference.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: "image/jpeg",
                reference.length, source = "user_attach")
        }).copy(messageId = user.id)
        fun input(history: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> =
            if (history.any { it.messageId == user.id }) history.map { if (it.messageId == user.id) historyUser else it }
            else history + historyUser
        return state.copy(messages = messages, history = input(state.history), journal = input(state.journal.ifEmpty { state.history }))
    }
}
