package io.github.mangi.eta.local.guipresentation

import android.os.SystemClock
import android.util.Log
import io.github.mangi.eta.core.toSafeLogToken
import java.util.concurrent.atomic.AtomicLong

/** Opt-in logcat only: setprop log.tag.EtaGuiPresentation DEBUG; set to INFO to disable. */
internal object GuiPresentationDiagnostics {
    private const val TAG = "EtaGuiPresentation"
    private val sequence = AtomicLong()
    val enabled: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)

    fun record(
        event: String,
        runId: String?,
        source: String?,
        entryVisible: String,
        overlayAttached: Boolean,
        owner: String?,
    ) {
        if (!enabled) return
        // INFO survives the existing Release DEBUG stripping; the tag is still explicitly opt-in.
        Log.i(TAG, "seq=${sequence.incrementAndGet()} monoMs=${SystemClock.elapsedRealtime()} " +
            "event=${event.toSafeLogToken()} runId=${runId.toSafeLogToken(128)} " +
            "source=${source.toSafeLogToken()} entryVisible=${entryVisible.toSafeLogToken()} " +
            "overlayAttached=$overlayAttached owner=${owner.toSafeLogToken(128)}")
    }
}
