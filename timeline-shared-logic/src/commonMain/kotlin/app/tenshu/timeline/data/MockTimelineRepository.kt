package app.tenshu.timeline.data

import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripEntry
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [TimelineRepository] over [TimelineMockData] (Ph2, docs/proposals/timeline-app.md
 * §2). The swappable seam — the real on-device store replaces this behind the interface with
 * one Koin binding. All reads are synchronous over held state; single-entity lookups walk the
 * dataset recursively so nested trips and their moments resolve uniformly.
 */
class MockTimelineRepository : TimelineRepository {

    private val days = MutableStateFlow(TimelineMockData.days)
    private val suggestion = MutableStateFlow<TripSuggestion?>(TimelineMockData.tripSuggestion)
    private val boundTrips = MutableStateFlow(TimelineMockData.boundTrips)
    private val places = MutableStateFlow(TimelineMockData.places)
    private val activities = MutableStateFlow(TimelineMockData.activities)

    override fun homeDays(): Flow<List<Day>> = days.asStateFlow()
    override fun tripSuggestion(): Flow<TripSuggestion?> = suggestion.asStateFlow()
    override fun boundTrips(): Flow<List<Trip>> = boundTrips.asStateFlow()
    override fun places(): Flow<List<Place>> = places.asStateFlow()
    override fun activities(): Flow<List<ActivityRollup>> = activities.asStateFlow()

    override suspend fun trip(id: String): Trip? =
        TimelineMockData.trips.firstNotNullOfOrNull { it.findTrip(id) }

    override suspend fun moment(id: String, fresh: Boolean): Moment? {
        days.value.forEach { day ->
            day.moments.firstOrNull { it.id == id }?.let { return it }
        }
        return TimelineMockData.trips.firstNotNullOfOrNull { it.findMoment(id) }
    }

    override suspend fun place(id: String): Place? = places.value.firstOrNull { it.id == id }

    /**
     * A movement's decoded path (M5). Unknown trace → empty, exactly like a real store whose polyline
     * was dropped, so the detail degrades to its stats-only anatomy rather than failing.
     */
    override suspend fun movementRoute(traceId: String): List<GeoPoint> =
        TimelineMockData.movementRoutes[traceId].orEmpty()

    override suspend fun renamePlace(id: String, label: String) {
        val newLabel = label.trim()
        if (newLabel.isEmpty()) return
        places.value = places.value.map { if (it.id == id) it.copy(name = newLabel) else it }
    }

    override suspend fun dismissTripSuggestion() { suggestion.value = null }

    override suspend fun acceptTripSuggestion() { suggestion.value = null }

    // ── Recursive lookups over the trip tree ────────────────────────────────

    private fun Trip.findTrip(id: String): Trip? {
        if (this.id == id) return this
        return contents.filterIsInstance<TripEntry.NestedTrip>()
            .firstNotNullOfOrNull { it.trip.findTrip(id) }
    }

    private fun Trip.findMoment(id: String): Moment? {
        contents.forEach { entry ->
            when (entry) {
                is TripEntry.DayEntry -> entry.moments.firstOrNull { it.id == id }?.let { return it }
                is TripEntry.MomentItem -> if (entry.moment.id == id) return entry.moment
                is TripEntry.NestedTrip -> entry.trip.findMoment(id)?.let { return it }
                is TripEntry.Collapsed -> Unit
            }
        }
        return null
    }
}
