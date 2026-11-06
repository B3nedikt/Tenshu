package org.tenshu.sdk.ios

import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The iOS **"not connected to Control"** Timeline repository — the exact twin of
 * [NotConnectedFitRepository] (`docs/proposals/fit-app.md` §11.7), and for the same reason.
 *
 * Timeline holds no data of its own until Control provisions the route-1 key material (or a
 * `DEV_HARNESS` EPC client pairs). Until then there is genuinely **nothing to show** — but the
 * shipping shell defaulted to [app.tenshu.timeline.data.MockTimelineRepository], so an
 * unprovisioned user saw invented places, invented trips and an invented "interlude" and had no way
 * to tell them from their own life. That is strictly worse than an empty app: fabricated data that
 * *looks* real is the `ImportStatus.Sample` anti-pattern Fit already retired.
 *
 * Every read here is empty and [controlReachable] is `false`, which is what drives the shell's
 * "Can't reach Tenshu Control" banner — the same signal the Compose shell renders off
 * `TimelineRepository.controlReachable()` (`timeline-shared-ui` `App.kt`).
 *
 * The mock stays — for tests and previews — but is no longer a *runtime* surface:
 * [TimelineSurface] installs this as its default and on teardown, and swaps in the real projection
 * repository when the route-1 runtime or the EPC client comes up.
 *
 * ⚠ The mutating members are deliberately **no-ops rather than throws**: unlike Fit's `addWorkout`
 * (a user-initiated save, where silence would look like data loss) every Timeline mutation here is
 * reachable only from a detail screen that cannot render without data. With every read empty they
 * are unreachable by construction, and the interface's own defaults are already no-ops.
 */
class NotConnectedTimelineRepository : TimelineRepository {

    // The load-bearing signal: `false` drives the shell's "Can't reach Tenshu Control" banner.
    override fun controlReachable(): StateFlow<Boolean?> = MutableStateFlow(false)

    override fun homeDays(): Flow<List<Day>> = flowOf(emptyList())
    override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
    override fun boundTrips(): Flow<List<Trip>> = flowOf(emptyList())
    override fun places(): Flow<List<Place>> = flowOf(emptyList())
    override fun activities(): Flow<List<ActivityRollup>> = flowOf(emptyList())

    override suspend fun trip(id: String): Trip? = null
    override suspend fun moment(id: String, fresh: Boolean): Moment? = null
    override suspend fun place(id: String): Place? = null

    override suspend fun dismissTripSuggestion() = Unit
    override suspend fun acceptTripSuggestion() = Unit
}
