package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The place the user is currently dwelling at, since [arrivalMs]. Coordinates are E7. */
data class OngoingVisit(val latE7: Long, val lngE7: Long, val arrivalMs: Long)

/**
 * Device-local live-capture state: the dwell the user is *currently* at, or `null`
 * (`docs/proposals/timeline-movement-capture.md` §11.2). Published by [LocationCaptureEngine] on the
 * confirmation `Dwell(ongoing = true)` and cleared on the final departure emit / a move / capture stop.
 *
 * The Timeline projector reads it to show a **live, ticking duration** for the current place — computed
 * as `now - arrivalMs` in the UI — so the moment is written just twice (confirm + departure) instead of
 * re-synced every minute (the option-A cost we rejected). It **never crosses the wire** (a per-device
 * concern, like `TripSuggestion`); capture and the UI are co-process in the Timeline app, so a plain
 * process-local singleton is the whole mechanism.
 *
 * ⚠ Mutually exclusive with [OngoingMoveState] — you are either dwelling or moving. [set] therefore
 * clears the move state, and its [OngoingMoveState.set] clears this one, so "both current" is
 * unrepresentable rather than merely discouraged.
 */
object OngoingVisitState {
    private val _current = MutableStateFlow<OngoingVisit?>(null)
    val current: StateFlow<OngoingVisit?> = _current.asStateFlow()

    fun set(visit: OngoingVisit) {
        OngoingMoveState.clearVisitExclusive()
        _current.value = visit
    }

    fun clear() {
        _current.value = null
    }

    /** Cleared because a move took over. Separate entry point so the two objects can't recurse. */
    internal fun clearMoveExclusive() {
        _current.value = null
    }
}
