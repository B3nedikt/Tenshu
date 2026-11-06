package app.tenshu.fit.data

import kotlinx.serialization.Serializable
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample

/**
 * The neutral, platform-free result of a [HealthImporter] read — the acquisition side of the
 * import seam (`docs/proposals/timeline-services.md` §4.2). A platform importer (Health Connect,
 * HealthKit, a FIT/GPX file parser, or the in-process [ReferenceHealthImporter]) maps its source
 * model into these plain DTOs in `commonMain`; [HealthImportWriter] then maps them onto the
 * `:health-service` schema + persists via the record ops. Split this way, *acquisition* is the
 * only platform-specific step — the mapping and the write path stay shared and testable.
 *
 * Instants are epoch-millis and dates are ISO-8601 `YYYY-MM-DD` strings, matching the
 * `:health-service` create factories. Empty lists are fine — a source that only exposes workouts
 * fills `workouts` and leaves the rest empty.
 *
 * `@Serializable` is for the **local, app-private workout outbox only** (`RecordedWorkoutOutbox`
 * — a recorder save is journalled here before the record ops run, mirroring Timeline's
 * `ImportedTimeline`). Never a wire format: the record ops carry these through the schema DSL
 * (House Rule #4 unaffected).
 */
@Serializable
data class ImportedHealth(
    val workouts: List<ImportedWorkout> = emptyList(),
    val dailyActivities: List<ImportedDailyActivity> = emptyList(),
    val dailyNutritions: List<ImportedDailyNutrition> = emptyList(),
    val menstruationDays: List<ImportedMenstruationDay> = emptyList(),
    val sleepSessions: List<ImportedSleepSession> = emptyList(),
    val samples: List<ImportedSample> = emptyList(),
    val medicalRecords: List<ImportedMedicalRecord> = emptyList(),
) {
    val isEmpty: Boolean
        get() = workouts.isEmpty() && dailyActivities.isEmpty() && dailyNutritions.isEmpty() &&
            menstruationDays.isEmpty() && sleepSessions.isEmpty() && samples.isEmpty() && medicalRecords.isEmpty()

    val count: Int
        get() = workouts.size + dailyActivities.size + dailyNutritions.size + menstruationDays.size +
            sleepSessions.size + samples.size + medicalRecords.size

    /**
     * Merges two partial reads. An archive import assembles its result from many files — a workout
     * per activity file, the days from a metrics CSV — and each contributes a different slice.
     * Concatenation, not deduplication: the record ops are content-addressed, so a genuine duplicate
     * folds to one row at the write.
     */
    operator fun plus(other: ImportedHealth): ImportedHealth = ImportedHealth(
        workouts = workouts + other.workouts,
        dailyActivities = dailyActivities + other.dailyActivities,
        dailyNutritions = dailyNutritions + other.dailyNutritions,
        menstruationDays = menstruationDays + other.menstruationDays,
        sleepSessions = sleepSessions + other.sleepSessions,
        samples = samples + other.samples,
        medicalRecords = medicalRecords + other.medicalRecords,
    )
}

@Serializable
data class ImportedWorkout(
    val workoutId: String,
    val activityType: String,
    val source: String,
    val startMs: Long,
    val endMs: Long,
    val durationSec: Long,
    val title: String = "",
    val distanceM: Long = 0,
    val activeEnergyKj: Long = 0,
    val avgHr: Long = 0,
    /** Steps counted during the session (a first-party recording); `0` when the source has none. */
    val steps: Long = 0,
    /**
     * The dense per-sample streams (a high-fidelity import — a full GPX track or a FIT file — fills
     * these; a bare summary import leaves them empty). Written to `Workout.Streams` by
     * [HealthImportWriter]; see `WorkoutStreams`.
     */
    val route: List<RouteSample> = emptyList(),
    val heartRate: List<HrSample> = emptyList(),
)

@Serializable
data class ImportedDailyActivity(
    val date: String,
    val steps: Long = 0,
    val distanceM: Long = 0,
    val activeEnergyKj: Long = 0,
    val activeMinutes: Long = 0,
)

@Serializable
data class ImportedDailyNutrition(
    val date: String,
    val energyKj: Long = 0,
    val hydrationMl: Long = 0,
)

@Serializable
data class ImportedMenstruationDay(
    val date: String,
    val flow: String,
    val periodStart: Boolean = false,
)

@Serializable
data class ImportedSleepSession(
    val date: String,
    val startMs: Long,
    val endMs: Long,
    val durationSec: Long,
    val source: String,
    val awakeMin: Long = 0,
    val remMin: Long = 0,
    val lightMin: Long = 0,
    val deepMin: Long = 0,
)

@Serializable
data class ImportedSample(
    val metric: String,
    val value: String,
    val unit: String,
    val timeMs: Long,
    val source: String,
    val resting: Boolean = false,
)

@Serializable
data class ImportedMedicalRecord(
    val recordId: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val effectiveDate: String,
    val issuer: String = "",
    val source: String,
)
