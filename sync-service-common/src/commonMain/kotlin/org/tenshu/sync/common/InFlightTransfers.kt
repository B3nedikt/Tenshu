package org.tenshu.sync.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * How many transfers are with an out-of-process uploader right now
 * (`ios-sync-activity-indicator.md` §6a.1): handed off and not yet reported complete.
 *
 * ⚠⚠ In flight, never "journalled". A transfer that failed stays in the journal with no retry
 * until a later launch, so journal occupancy would say "uploading" with nothing running.
 *
 * Counted **per ref**, not as one integer: a completion for a ref this does not hold is ignored,
 * so a stray or replayed completion cannot cancel out a different live transfer. One ref can have
 * several live transfers (a resume re-enqueues what is already running), so each ref has a count.
 *
 * Where it could be wrong it errs low: the indicator may end early, it cannot stick.
 */
class InFlightTransfers {

    private data class State(
        val live: Map<String, Int> = emptyMap(),
        val seeding: Boolean = false,
        val completedWhileSeeding: Map<String, Int> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    /** Transfers in flight, over every ref. */
    val count: Flow<Int> = state.map { it.live.values.sum() }.distinctUntilChanged()

    val current: Int get() = state.value.live.values.sum()

    /** A transfer of [ref] was handed to the uploader. */
    fun handedOff(ref: String) {
        state.update { it.copy(live = it.live.plusOne(ref)) }
    }

    /** The uploader reported a transfer of [ref] finished, whatever the outcome. */
    fun completed(ref: String) {
        state.update {
            it.copy(
                live = it.live.minusOne(ref),
                completedWhileSeeding = if (it.seeding) it.completedWhileSeeding.plusOne(ref) else it.completedWhileSeeding,
            )
        }
    }

    /**
     * The uploader is about to be asked which transfers it already holds. From here until [seed],
     * completions are remembered, so a transfer that finishes while the answer is outstanding is
     * not then seeded as live for ever.
     */
    fun beginSeed() {
        state.update { it.copy(seeding = true, completedWhileSeeding = emptyMap()) }
    }

    /**
     * The uploader's own list of live transfers, one entry per transfer. Each ref ends up with
     * the larger of what was already counted and what the list says, less what completed since
     * [beginSeed] — the larger, not the sum, because a hand-off made in the meantime may or may
     * not be in the list.
     */
    fun seed(liveRefs: List<String>) {
        val listed = liveRefs.groupingBy { it }.eachCount()
        state.update { before ->
            val live = (before.live.keys + listed.keys).associateWith { ref ->
                val stillListed = (listed[ref] ?: 0) - (before.completedWhileSeeding[ref] ?: 0)
                maxOf(before.live[ref] ?: 0, stillListed)
            }.filterValues { it > 0 }
            State(live = live)
        }
    }

    private fun Map<String, Int>.plusOne(ref: String): Map<String, Int> = this + (ref to ((this[ref] ?: 0) + 1))

    private fun Map<String, Int>.minusOne(ref: String): Map<String, Int> {
        val now = this[ref] ?: return this
        return if (now <= 1) this - ref else this + (ref to now - 1)
    }
}
