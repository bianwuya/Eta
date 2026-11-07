package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandTest {
    @Test fun softKeyboardCommitDoesNotRequireAKeyEvent() {
        val tracker = SlashCommandTracker()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        assertEquals(SlashTrigger("/", 0), tracker.takeCandidate())
    }

    @Test fun nestedSingleCommitBatchKeepsEvidenceUntilTransformation() {
        val tracker = SlashCommandTracker()
        tracker.beginImeBatch()
        tracker.beginImeBatch()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.endImeBatch()
        tracker.userEdit("/", "")
        tracker.endImeBatch()
        assertEquals(SlashTrigger("/", 0), tracker.takeCandidate())
    }

    @Test fun selectionOnlyTransformationDoesNotConsumePendingCandidate() {
        val tracker = SlashCommandTracker()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        tracker.userEdit("/", "/")
        assertEquals(SlashTrigger("/", 0), tracker.takeCandidate())
    }

    @Test fun multiCommitOrIndirectBatchCannotTurnIntoADirectSlash() {
        val tracker = SlashCommandTracker()
        tracker.beginImeBatch()
        tracker.imeCommit("", 0, 0, "word", composing = false)
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        assertNull(tracker.takeCandidate())
        tracker.endImeBatch()
        tracker.beginImeBatch()
        tracker.indirectImeEdit() // composition/context-menu paste before commit in the same batch
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        assertNull(tracker.takeCandidate())
        tracker.endImeBatch()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        assertEquals(SlashTrigger("/", 0), tracker.takeCandidate())
    }

    @Test fun compositionBulkCommitAndStaleProgrammaticDraftDoNotTrigger() {
        val tracker = SlashCommandTracker()
        listOf("//", " /", "text/", null).forEach { text ->
            tracker.imeCommit("", 0, 0, text, composing = false)
            tracker.userEdit("/", "")
            assertNull(tracker.takeCandidate())
        }
        tracker.imeCommit("", 0, 0, "/", composing = true)
        tracker.userEdit("/", "")
        assertNull(tracker.takeCandidate())
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "restored")
        assertNull(tracker.takeCandidate())
    }

    @Test fun contextPasteOrClosedConnectionDropsPendingCandidate() {
        val tracker = SlashCommandTracker()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        tracker.indirectImeEdit()
        assertNull(tracker.takeCandidate())
        tracker.beginImeBatch()
        tracker.indirectImeEdit()
        tracker.closeImeSession()
        tracker.imeCommit("", 0, 0, "/", composing = false)
        tracker.userEdit("/", "")
        assertEquals(SlashTrigger("/", 0), tracker.takeCandidate())
    }

    @Test fun standaloneKeyboardSlashIsBoundToTheExactDraft() {
        val tracker = SlashCommandTracker()
        tracker.arm("before  after", 7, 7, composing = false)
        tracker.userEdit("before / after")
        assertEquals(SlashTrigger("before / after", 7), tracker.takeCandidate())
        assertNull(tracker.takeCandidate())
    }

    @Test fun pasteOrProgrammaticChangesWithoutKeyboardEvidenceDoNotArm() {
        val tracker = SlashCommandTracker()
        tracker.userEdit("/")
        assertNull(tracker.takeCandidate())
        tracker.arm("", 0, 0, composing = false)
        tracker.clear() // e.g. external synchronization/dictation changes the input
        tracker.userEdit("/")
        assertNull(tracker.takeCandidate())
    }

    @Test fun pathsUrlsWordsAndComposingDoNotTrigger() {
        val tracker = SlashCommandTracker()
        listOf("https:", "path", "word").forEach { before ->
            tracker.arm(before, before.length, before.length, composing = false)
            tracker.userEdit("$before/")
            assertNull(tracker.takeCandidate())
        }
        tracker.arm("abc", 0, 0, composing = false)
        tracker.userEdit("/abc")
        assertNull(tracker.takeCandidate())
        tracker.arm("", 0, 0, composing = true)
        tracker.userEdit("/")
        assertNull(tracker.takeCandidate())
    }

    @Test fun selectionIsReplacedOnlyByAnExactSingleSlash() {
        val tracker = SlashCommandTracker()
        tracker.arm("a replace b", 9, 2, composing = false)
        tracker.userEdit("a / b")
        assertEquals(SlashTrigger("a / b", 2), tracker.takeCandidate())
        tracker.arm("a  b", 2, 2, composing = false)
        tracker.userEdit("a // b")
        assertNull(tracker.takeCandidate())
    }

    @Test fun removalMapsForwardAndReversedSelectionsWithoutChangingOtherText() {
        val trigger = SlashTrigger("a / b", 2)
        assertEquals(SlashRemoval("a  b", 1, 4), removeSlashTrigger(trigger, trigger.text, 1, 5, false))
        assertEquals(SlashRemoval("a  b", 4, 1), removeSlashTrigger(trigger, trigger.text, 5, 1, false))
        assertEquals(SlashRemoval("a  b", 2, 2), removeSlashTrigger(trigger, trigger.text, 3, 3, false))
    }

    @Test fun staleOrComposingCandidateNeverDeletes() {
        val trigger = SlashTrigger("a / b", 2)
        assertNull(removeSlashTrigger(trigger, "a / bc", 3, 3, false))
        assertNull(removeSlashTrigger(trigger, trigger.text, 3, 3, true))
        assertNull(removeSlashTrigger(trigger, trigger.text, 8, 8, false))
        assertNull(removeSlashTrigger(SlashTrigger("a   b", 2), "a   b", 2, 2, false))
    }

    @Test fun requestModesAreRestrictedToNormalAndPlan() {
        assertEquals("plan", normalizedRequestMode("plan"))
        assertEquals("normal", normalizedRequestMode("normal"))
        assertEquals("normal", normalizedRequestMode("PLAN"))
        assertEquals("normal", normalizedRequestMode(""))
    }

    @Test fun recallUsesClickTimeLocalModeAndPrefersTheModeCallback() {
        var legacyCalls = 0
        var received: Triple<String, String, String>? = null
        recallQueuedDraft("queued", "unsent local draft", "plan", { _, _ -> legacyCalls++ },
            { id, text, mode -> received = Triple(id, text, mode) })
        assertEquals(0, legacyCalls)
        assertEquals(Triple("queued", "unsent local draft", "plan"), received)
        recallQueuedDraft("queued", "next draft", "normal", { _, _ -> legacyCalls++ },
            { id, text, mode -> received = Triple(id, text, mode) })
        assertEquals(Triple("queued", "next draft", "normal"), received)
    }

    @Test fun recallKeepsTheLegacyCallbackCompatible() {
        var received: Pair<String, String>? = null
        recallQueuedDraft("queued", "local draft", "plan", { id, text -> received = id to text }, null)
        assertEquals("queued" to "local draft", received)
    }
}
