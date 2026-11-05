package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDrawerGroupsTest {
    private val now = millis("2026-11-06T00:14:00")

    @Test
    fun emptyListHasNoHeaders() {
        assertTrue(emptyList<ConversationSummaryUi>().groupForDrawer(now).isEmpty())
    }

    @Test
    fun nonadjacentActiveRunsShareOneTodayHeader() {
        val rows = listOf(
            row("today", "2026-11-06T00:01:00"),
            row("yesterday", "2026-11-05T23:59:00"),
            row("active", "2026-11-04T22:00:00", active = true),
            row("older", "2026-11-04T21:00:00", label = "Nov 4"),
            row("active-older", "2026-11-03T21:00:00", active = true),
        )
        val groups = rows.groupForDrawer(now)
        assertEquals(listOf("today", "active", "active-older"), groups.first().items.map { it.id })
        assertEquals(ConversationDrawerSection.Today, groups.first().section)
        assertUniqueAndComplete(rows, groups)
    }

    @Test
    fun pinnedAndTodayPrecedeDatedGroupsWithoutChangingWithinGroupOrder() {
        val rows = listOf(
            row("dated", "2026-11-05T23:59:00"),
            row("active", "2026-11-04T22:00:00", active = true),
            row("pin-one", "2026-11-03T20:00:00", pinned = true, active = true),
            row("pin-two", "2026-11-02T20:00:00", pinned = true),
        )
        val groups = rows.groupForDrawer(now)
        assertEquals(
            listOf(
                ConversationDrawerSection.Pinned,
                ConversationDrawerSection.Today,
                ConversationDrawerSection.Dated("Yesterday"),
            ),
            groups.map { it.section },
        )
        assertEquals(listOf("pin-one", "pin-two"), groups.first().items.map { it.id })
        assertUniqueAndComplete(rows, groups)
    }

    @Test
    fun nonadjacentDatedLabelsHaveOneHeaderAndStableOrder() {
        val rows = listOf(
            row("a", "2026-11-05T22:00:00", label = "Nov 5"),
            row("b", "2026-11-04T22:00:00", label = "Nov 4"),
            row("c", "2026-11-05T21:00:00", label = "Nov 5"),
        )
        val groups = rows.groupForDrawer(now)
        assertEquals(listOf("Nov 5", "Nov 4"), groups.map {
            (it.section as ConversationDrawerSection.Dated).label
        })
        assertEquals(listOf("a", "c"), groups.first().items.map { it.id })
        assertUniqueAndComplete(rows, groups)
    }

    @Test
    fun midnightUsesOneDateForEntireSnapshot() {
        val rows = listOf(
            row("before", "2026-11-05T23:59:59"),
            row("after", "2026-11-06T00:00:00"),
            row("running-before", "2026-11-05T23:58:00", active = true),
        )
        val groups = rows.groupForDrawer(millis("2026-11-06T00:00:00"))
        assertEquals(listOf("after", "running-before"), groups.first().items.map { it.id })
        assertEquals(listOf("before"), groups.last().items.map { it.id })
        assertUniqueAndComplete(rows, groups)
    }

    @Test
    fun missingTimestampKeepsExistingTodayBehavior() {
        val rows = listOf(
            row("old", "2026-11-05T20:00:00"),
            row("unknown", "2026-11-04T20:00:00").copy(updatedAtMillis = 0),
        )
        val groups = rows.groupForDrawer(now)
        assertEquals(ConversationDrawerSection.Today, groups.first().section)
        assertEquals("unknown", groups.first().items.single().id)
        assertUniqueAndComplete(rows, groups)
    }

    @Test
    fun endingAnOldRunMovesItToItsDatedGroupWithoutDuplicateHeaders() {
        val rows = listOf(
            row("today", "2026-11-06T00:01:00"),
            row("old", "2026-11-05T22:00:00"),
            row("run", "2026-11-05T21:00:00", active = true),
        )
        assertUniqueAndComplete(rows, rows.groupForDrawer(now))
        val ended = rows.map { it.copy(isActiveRun = false) }
        val groups = ended.groupForDrawer(now)
        assertEquals(listOf("old", "run"), groups.last().items.map { it.id })
        assertUniqueAndComplete(ended, groups)
    }

    private fun assertUniqueAndComplete(rows: List<ConversationSummaryUi>, groups: List<ConversationDrawerGroup>) {
        val keys = groups.map { "section-${it.section}" }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(rows.map { it.id }.sorted(), groups.flatMap { it.items }.map { it.id }.sorted())
    }

    private fun row(
        id: String,
        updated: String,
        label: String = "Yesterday",
        active: Boolean = false,
        pinned: Boolean = false,
    ) = ConversationSummaryUi(
        id = id, title = id, preview = "", timeLabel = label,
        updatedAtMillis = millis(updated), mode = ConversationModeUi.Chat,
        isActiveRun = active, isPinned = pinned,
    )

    private fun millis(value: String): Long =
        LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
