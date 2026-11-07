package io.github.mangi.eta.agent.runtime

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRunSnapshotConflationTest {
    @Test fun saturationKeepsMostRecentTerminalSnapshot() {
        val snapshots = Channel<AgentRuntimeClient.ActiveRunsUpdate>(Channel.CONFLATED)
        val tracker = ActiveRunsChangeTracker()
        repeat(200) { index ->
            assertTrue(snapshots.trySend(tracker.update(listOf(AgentRuntimeWire.ActiveRunSnapshot("run-$index", "conversation")))).isSuccess)
        }
        assertTrue(snapshots.trySend(tracker.update(emptyList())).isSuccess)
        val last = requireNotNull(snapshots.tryReceive().getOrNull())
        assertEquals(emptyList<AgentRuntimeWire.ActiveRunSnapshot>(), last.snapshots)
        assertEquals(200L, last.terminalRevision)
        snapshots.close()
    }
}
