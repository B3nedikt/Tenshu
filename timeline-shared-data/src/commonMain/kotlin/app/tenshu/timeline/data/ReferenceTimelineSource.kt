package app.tenshu.timeline.data

import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind

/**
 * A **deterministic in-process [TimelineImportSource]** — the reference implementation of the
 * ingestion seam, standing in for a real Google-Timeline import (I2) or live location capture (I3)
 * until those land. It emits the small service-shaped fixture the former `TimelineSeeder` wrote,
 * now produced *through the import contract* ([ImportedTimeline]) and persisted by the shared
 * [TimelineImportWriter] — so **seeding and importing are the same code path** (mirrors Fit's
 * `ReferenceHealthImporter`, which replaced `FitSeeder`).
 *
 * Its purpose is to light up the projector over real, synced entities so the Chapters screens
 * render over the same read path production capture/import will use. ⚠ The spine-only entities
 * carry no photos, weather, or trip contents, so the projected screens are *thinner* than the
 * hand-built mock until the deferred slices land.
 */
class ReferenceTimelineSource : TimelineImportSource {

    override suspend fun load(): ImportedTimeline {
        val midnightUtc = 1_799_971_200_000L
        val hour = 3_600_000L

        return ImportedTimeline(
            // The subjects the moments below point at.
            places = listOf(
                ImportedPlace(
                    placeId = PLACE_CAFE,
                    latE7 = 525_200_000L, lngE7 = 133_800_000L,
                    capturedAtMs = midnightUtc, label = "Blue Bottle",
                ),
            ),
            workouts = listOf(
                ImportedWorkout(
                    workoutId = WORKOUT_RUN, activityType = "Running", source = "Manual",
                    startMs = midnightUtc + 9 * hour, endMs = midnightUtc + 9 * hour + 1_920_000L,
                    durationSec = 1_920,
                ),
            ),
            // Two moments on the same day — an Activity (→ the workout) and a Visit (→ the place).
            moments = listOf(
                ImportedMoment(
                    momentId = MOMENT_RUN, timestampMs = midnightUtc + 9 * hour,
                    kind = Moment.MomentKind.ACTIVITY, title = "Morning run",
                    subjectKind = SubjectKind.WORKOUT, subjectId = WORKOUT_RUN, durationSec = 1_920,
                ),
                ImportedMoment(
                    momentId = MOMENT_COFFEE, timestampMs = midnightUtc + 11 * hour,
                    kind = Moment.MomentKind.VISIT, title = "Coffee",
                    subjectKind = SubjectKind.PLACE, subjectId = PLACE_CAFE, durationSec = 1_080,
                ),
            ),
            // A bound trip (a curation).
            trips = listOf(
                ImportedTrip(tripId = TRIP_LISBON, title = "Lisbon", dateRange = "Apr 12 – 17", year = "2026"),
            ),
        )
    }

    companion object {
        const val PLACE_CAFE = "seed-place-cafe"
        const val WORKOUT_RUN = "seed-workout-run"
        const val MOMENT_RUN = "seed-moment-run"
        const val MOMENT_COFFEE = "seed-moment-coffee"
        const val TRIP_LISBON = "seed-trip-lisbon"
    }
}
