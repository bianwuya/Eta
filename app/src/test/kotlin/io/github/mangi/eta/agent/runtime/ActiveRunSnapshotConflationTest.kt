package io.github.mangi.eta.agent.runtime

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRunSnapshotConflationTest {
    @Test fun saturationKeepsMostRecentTerminalSnapshot() {
        val snapshots = Channel<List<AgentRuntimeWire.ActiveRunSnapshot>>(Channel.CONFLATED)
        repeat(200) { index ->
            assertTrue(snapshots.trySend(listOf(AgentRuntimeWire.ActiveRunSnapshot("run-$index", "conversation"))).isSuccess)
        }
        assertTrue(snapshots.trySend(emptyList()).isSuccess)
        assertEquals(emptyList<AgentRuntimeWire.ActiveRunSnapshot>(), snapshots.tryReceive().getOrNull())
        snapshots.close()
    }
}
