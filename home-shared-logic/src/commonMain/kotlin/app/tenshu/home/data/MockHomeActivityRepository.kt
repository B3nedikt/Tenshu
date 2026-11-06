package app.tenshu.home.data

import app.tenshu.home.model.ActivityEvent
import app.tenshu.home.model.ActivityKind
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory mock (docs/proposals/home-and-mia-apps.md §3): a plausible recent history
 * across all three kinds, seeded relative to construction time so the feed always reads
 * as "today / yesterday". Newest-first, matching the repository contract.
 */
class MockHomeActivityRepository(
    seed: List<ActivityEvent> = defaultSeed(Clock.System.now()),
) : HomeActivityRepository {

    private val eventsFlow = MutableStateFlow(seed.sortedByDescending { it.at })

    override fun events(): Flow<List<ActivityEvent>> = eventsFlow

    companion object {
        fun defaultSeed(now: Instant): List<ActivityEvent> = listOf(
            ActivityEvent(
                id = "event-1",
                at = now - 12.minutes,
                kind = ActivityKind.Device,
                title = "Balcony door",
                detail = "Opened",
            ),
            ActivityEvent(
                id = "event-2",
                at = now - 47.minutes,
                kind = ActivityKind.Voice,
                title = "Mia",
                detail = "\"Turn on the sofa lamp\"",
            ),
            ActivityEvent(
                id = "event-3",
                at = now - 3.hours,
                kind = ActivityKind.Automation,
                title = "Welcome home",
                detail = "Turned on Sofa lamp",
            ),
            ActivityEvent(
                id = "event-4",
                at = now - 3.hours - 1.minutes,
                kind = ActivityKind.Device,
                title = "Front door camera",
                detail = "Person detected",
            ),
            ActivityEvent(
                id = "event-5",
                at = now - 14.hours,
                kind = ActivityKind.Automation,
                title = "Lights out",
                detail = "Turned off 2 lights",
            ),
            ActivityEvent(
                id = "event-6",
                at = now - 1.days - 2.hours,
                kind = ActivityKind.Device,
                title = "Kitchen thermostat",
                detail = "Heating started",
            ),
            ActivityEvent(
                id = "event-7",
                at = now - 1.days - 5.hours,
                kind = ActivityKind.Voice,
                title = "Mia",
                detail = "\"How warm is it in the kitchen?\"",
            ),
        )
    }
}
