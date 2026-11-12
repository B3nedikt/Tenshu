package app.tenshu.control.bridge

import app.tenshu.control.bridge.ExplorerStatus.Reason
import app.tenshu.control.bridge.ExplorerStatus.State
import kotlin.test.Test
import kotlin.test.assertEquals

class ExplorerStatusTest {

    @Test
    fun nothingPending_isUpToDate() {
        assertEquals(ExplorerStatus(State.UP_TO_DATE), ExplorerStatus.of(emptySet(), emptyMap(), unreachable = false))
    }

    @Test
    fun somethingOnItsWay_isSyncing() {
        val status = ExplorerStatus.of(setOf("a.txt", "b.txt"), emptyMap(), unreachable = false)
        assertEquals(State.SYNCING, status.state)
        assertEquals(2, status.pending)
    }

    @Test
    fun onlyFailuresLeft_isNotSyncing_andTooLargeComesFirst() {
        val status = ExplorerStatus.of(
            setOf("a.txt", "big.iso"), mapOf("a.txt" to Reason.RETRYING, "big.iso" to Reason.TOO_LARGE), unreachable = false,
        )
        assertEquals(State.UP_TO_DATE, status.state)
        assertEquals(listOf("big.iso", "a.txt"), status.failures.map { it.relative })
    }

    @Test
    fun aFailureNoLongerPending_isForgotten() {
        val status = ExplorerStatus.of(emptySet(), mapOf("gone.txt" to Reason.RETRYING), unreachable = false)
        assertEquals(emptyList(), status.failures)
    }

    @Test
    fun unreachableWins() {
        assertEquals(State.UNREACHABLE, ExplorerStatus.of(setOf("a"), emptyMap(), unreachable = true).state)
    }
}
