package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse

/** R-iOS-6: the courtesy HealthKit export skips cleanly where it can't (or shouldn't) write. */
class IosHealthKitWorkoutWriterTest {

    @Test
    fun export_is_a_noop_without_the_update_usage_string() = runTest {
        // The bare iosTest host declares no NSHealthUpdateUsageDescription, so export must skip
        // cleanly — no share-authorization prompt, no process abort — and report failure. This is
        // the same guard that keeps a non-Fit host (the umbrella's assembly) from writing.
        val at = LocalDateTime(2026, 7, 18, 9, 0, 0)
        val workout = Workout(
            workoutId = "rec-1",
            activityType = ActivityType.Running,
            source = Source.Recorded,
            title = "",
            device = "",
            start = at,
            end = at,
            durationSec = 600,
        )
        assertFalse(
            IosHealthKitWorkoutWriter().export(workout, startEpochMs = 0L, endEpochMs = 600_000L),
            "export must no-op without NSHealthUpdateUsageDescription",
        )
    }
}
