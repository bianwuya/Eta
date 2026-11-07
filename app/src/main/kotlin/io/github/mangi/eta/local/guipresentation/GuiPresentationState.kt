package io.github.mangi.eta.local.guipresentation

/** Visual continuity only. This class never owns or delays the GUI lock. */
internal class GuiPresentationState(private val releaseDelayMs: Long = 500L) {
    enum class Phase { HIDDEN, OWNED, USER_PAUSED, RELEASING }

    var phase = Phase.HIDDEN
        private set
    var displayedRunId: String? = null
        private set
    var releaseDeadlineMs: Long? = null
        private set
    var revision = 0L
        private set
    var yielded = false
        private set
    private var confirmedRunId: String? = null

    val touchable: Boolean
        get() = !yielded && (phase == Phase.OWNED || phase == Phase.USER_PAUSED)

    init { require(releaseDelayMs in 300L..800L) }

    fun update(ownerRunId: String?, userPaused: Boolean, nowMs: Long) {
        when {
            ownerRunId != null -> {
                val nextPhase = if (userPaused) Phase.USER_PAUSED else Phase.OWNED
                if (displayedRunId == ownerRunId && phase == nextPhase) return
                if (displayedRunId != ownerRunId) {
                    yielded = false
                    confirmedRunId = null
                }
                displayedRunId = ownerRunId
                phase = nextPhase
                releaseDeadlineMs = null
                revision++
            }
            userPaused -> {
                if (phase == Phase.USER_PAUSED) return
                phase = Phase.USER_PAUSED
                releaseDeadlineMs = null
                revision++
            }
            phase == Phase.OWNED || phase == Phase.USER_PAUSED -> {
                confirmedRunId = null
                phase = Phase.RELEASING
                releaseDeadlineMs = nowMs + releaseDelayMs
                revision++
            }
        }
    }

    fun setYielded(value: Boolean) { yielded = value }

    fun confirmVisible(runId: String?) {
        if (phase == Phase.OWNED && runId == displayedRunId) confirmedRunId = runId
    }

    fun forgetVisible() { confirmedRunId = null }

    /** Only a previously shown, still-owned capsule may use its original temporary hide path. */
    fun hasControlledPresentation(runId: String, materializing: Boolean): Boolean =
        phase == Phase.OWNED && displayedRunId == runId && confirmedRunId == runId &&
            (yielded || materializing)

    fun expire(expectedRevision: Long, nowMs: Long): Boolean {
        val deadline = releaseDeadlineMs ?: return false
        if (revision != expectedRevision || nowMs < deadline) return false
        clear()
        return true
    }

    fun terminal(runId: String) {
        if (displayedRunId == runId) clear()
    }

    fun clear() {
        phase = Phase.HIDDEN
        displayedRunId = null
        releaseDeadlineMs = null
        yielded = false
        confirmedRunId = null
        revision++
    }
}
