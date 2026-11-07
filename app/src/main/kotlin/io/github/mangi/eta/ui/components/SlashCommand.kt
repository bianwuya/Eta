package io.github.mangi.eta.ui.components

/** A particular slash insertion, not just any slash that happens to occur in the draft. */
internal data class SlashTrigger(val text: String, val offset: Int)

/** Tracks one proven input event, never scans restored drafts for arbitrary slashes. */
internal class SlashCommandTracker {
    private data class Armed(val before: String, val start: Int, val end: Int)
    private var armed: Armed? = null
    private var candidate: SlashTrigger? = null
    private var batchDepth = 0
    private var batchChanged = false

    fun beginImeBatch() {
        if (batchDepth == 0) batchChanged = false
        batchDepth++
    }

    fun endImeBatch() {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) batchChanged = false
    }

    /** Composition, context paste, correction and replacement are not direct symbol typing. */
    fun indirectImeEdit() {
        clear()
        if (batchDepth > 0) batchChanged = true
    }

    fun closeImeSession() {
        clear()
        batchDepth = 0
        batchChanged = false
    }

    fun arm(before: String, selectionStart: Int, selectionEnd: Int, composing: Boolean) {
        armed = null
        candidate = null
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (composing || start < 0 || end > before.length) return
        // A standalone slash cannot be part of a URL, path, or another word.
        if (start > 0 && !before[start - 1].isWhitespace()) return
        if (end < before.length && !before[end].isWhitespace()) return
        armed = Armed(before, start, end)
    }

    /** Soft keyboards normally commitText rather than sending slash KeyEvents. */
    fun imeCommit(before: String, selectionStart: Int, selectionEnd: Int, committed: String?, composing: Boolean) {
        clear()
        if (committed == "/" && !batchChanged) arm(before, selectionStart, selectionEnd, composing)
        if (batchDepth > 0) batchChanged = true
    }

    fun userEdit(after: String, originalText: String? = null) {
        // Some IMEs follow commitText with selection-only updates before snapshotFlow runs.
        if (armed == null && originalText == after) return
        val event = armed
        armed = null
        candidate = null
        if (event == null || (originalText != null && event.before != originalText)) return
        if (after == event.before.replaceRange(event.start, event.end, "/")) {
            candidate = SlashTrigger(after, event.start)
        }
    }

    fun clearArm() { armed = null }
    fun clear() { armed = null; candidate = null }
    fun takeCandidate(): SlashTrigger? = candidate.also { candidate = null }
}

internal data class SlashRemoval(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** Deletes only the same still-present trigger and maps both ends, including reversed selections. */
internal fun removeSlashTrigger(
    trigger: SlashTrigger,
    currentText: String,
    selectionStart: Int,
    selectionEnd: Int,
    composing: Boolean,
): SlashRemoval? {
    val offset = trigger.offset
    if (composing || currentText != trigger.text || offset !in currentText.indices ||
        currentText[offset] != '/' || selectionStart !in 0..currentText.length ||
        selectionEnd !in 0..currentText.length) return null
    fun map(position: Int): Int = if (position <= offset) position else position - 1
    return SlashRemoval(currentText.removeRange(offset, offset + 1), map(selectionStart), map(selectionEnd))
}

internal fun normalizedRequestMode(value: String): String = if (value == "plan") "plan" else "normal"

/** Both callbacks consume the click-time local draft, never a possibly stale parent snapshot. */
internal fun recallQueuedDraft(
    id: String,
    text: String,
    mode: String,
    legacy: (String, String) -> Unit,
    withMode: ((String, String, String) -> Unit)?,
) {
    if (withMode != null) withMode(id, text, normalizedRequestMode(mode)) else legacy(id, text)
}
