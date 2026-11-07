package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.util.Calendar

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

internal fun List<ConversationSummaryUi>.groupForDrawer(
    nowMillis: Long = System.currentTimeMillis(),
): List<ConversationDrawerGroup> {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    // Active older runs can be interleaved with dated rows; each section owns one lazy-list key.
    return groupBy { conversation -> conversation.drawerSection(now) }
        .map { (section, items) -> ConversationDrawerGroup(section, items) }
        .sortedBy { group ->
            when (group.section) {
                ConversationDrawerSection.Pinned -> 0
                ConversationDrawerSection.Today -> 1
                is ConversationDrawerSection.Dated -> 2
            }
        }
}

private fun ConversationSummaryUi.drawerSection(now: Calendar): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis, now) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long, now: Calendar): Boolean {
    if (timestampMillis <= 0L) return true
    val target = Calendar.getInstance(now.timeZone).apply { timeInMillis = timestampMillis }
    return now.get(Calendar.ERA) == target.get(Calendar.ERA) &&
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
}
