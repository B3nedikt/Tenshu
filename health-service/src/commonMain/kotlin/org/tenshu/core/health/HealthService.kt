package org.tenshu.core.health

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The Health service (`docs/proposals/timeline-services.md` §4) — the store of neutral
 * [Workout] records. Named for the umbrella (HealthKit / Health Connect are both "health"),
 * future-proof for the Tenshu Fit app + sleep/vitals; `Activity` stays a UI-layer view.
 * Import from platform APIs / files is a per-platform seam (§4.2), not part of this synced
 * surface.
 */
interface HealthService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Every recorded workout. */
    val workouts: TList<Workout>

    /** Every per-day activity aggregate (steps / distance / energy / active minutes). */
    val dailyActivities: TList<DailyActivity>

    /** Every per-day nutrition aggregate (energy consumed / hydration). */
    val dailyNutritions: TList<DailyNutrition>

    /** Every logged menstruation day (flow + period-start marker). */
    val menstruationDays: TList<MenstruationDay>

    /** Every recorded sleep session (one per night). */
    val sleepSessions: TList<SleepSession>

    /** The full sample time-series (every timestamped reading of every metric). */
    val healthSamples: TList<HealthSample>

    /**
     * The maintained latest-per-metric rollup — one [LatestSample] per `(metric, resting)`,
     * locally derived from [healthSamples] saves (never synced; see [LatestSample]). Teasers
     * and current-value headers read this instead of scanning the time-series.
     */
    val latestSamples: TList<LatestSample>

    /**
     * The maintained per-activity workout rollup — one [WorkoutActivityRollup] per activity type,
     * locally derived from [workouts] saves (never synced; see [WorkoutActivityRollup]). Any
     * "how many of each" surface reads this instead of grouping the whole workout history, a read
     * that does not complete after a full history import.
     */
    val workoutActivityRollups: TList<WorkoutActivityRollup>

    /** Every imported medical record (medication / immunization / allergy / condition). */
    val medicalRecords: TList<MedicalRecord>

    /**
     * Every standing platform health connection, across the user's devices — one row per
     * (kind, device). Synced, which is what lets one device show another's Apple Health state
     * (`docs/proposals/fit-data-sources-restructure.md` §2.1). ⚠ Only genuine connections live
     * here; Takeout and Fit's own recordings are not rows (D11).
     */
    val sources: TList<HealthSource>

    /** Every recorded import run, across devices — the durable history behind each source. */
    val importRuns: TList<HealthImportRun>

    companion object {
        const val ID = "tenshu/health/service"

        // `maintained = true` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:health-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 = release baseline (core-schema-evolution.md Appendix A).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.WORKOUTS,
                    type = TList.typeOf(Workout.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DAILY_ACTIVITIES,
                    type = TList.typeOf(DailyActivity.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DAILY_NUTRITIONS,
                    type = TList.typeOf(DailyNutrition.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.MENSTRUATION_DAYS,
                    type = TList.typeOf(MenstruationDay.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.SLEEP_SESSIONS,
                    type = TList.typeOf(SleepSession.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.HEALTH_SAMPLES,
                    type = TList.typeOf(HealthSample.Schema),
                    maintained = true,
                ),
                // ⚠ Deliberately **not `maintained`**: `LATEST_SAMPLES` is a rollup, not
                // membership — `LatestSampleMaintainer` computes one row per
                // `(metric, resting)` with a newer-wins comparison, which is not
                // "mirror the saved row into an array" at all
                // (`core-relation-system.md` §9.2, "deliberate exclusions").
                // It keeps its own observer.
                serviceDataRelation(
                    id = Relations.LATEST_SAMPLES,
                    type = TList.typeOf(LatestSample.Schema)
                ),
                serviceDataRelation(
                    id = Relations.WORKOUT_ACTIVITY_ROLLUPS,
                    type = TList.typeOf(WorkoutActivityRollup.Schema)
                ),
                serviceDataRelation(
                    id = Relations.MEDICAL_RECORDS,
                    type = TList.typeOf(MedicalRecord.Schema),
                    maintained = true,
                ),
                // Both `maintained`: membership relations keyed by the composite's own id
                // provider, which is exactly what generic maintenance derives — so
                // `:health-service-proxy` registers no observer of its own.
                serviceDataRelation(
                    id = Relations.SOURCES,
                    type = TList.typeOf(HealthSource.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.IMPORT_RUNS,
                    type = TList.typeOf(HealthImportRun.Schema),
                    maintained = true,
                )
            )
        )
    }

    object Relations {
        const val WORKOUTS = "$ID/workouts"
        const val DAILY_ACTIVITIES = "$ID/dailyActivities"
        const val DAILY_NUTRITIONS = "$ID/dailyNutritions"
        const val MENSTRUATION_DAYS = "$ID/menstruationDays"
        const val SLEEP_SESSIONS = "$ID/sleepSessions"
        const val HEALTH_SAMPLES = "$ID/healthSamples"
        const val LATEST_SAMPLES = "$ID/latestSamples"
        const val WORKOUT_ACTIVITY_ROLLUPS = "$ID/workoutActivityRollups"
        const val MEDICAL_RECORDS = "$ID/medicalRecords"
        const val SOURCES = "$ID/sources"
        const val IMPORT_RUNS = "$ID/importRuns"
    }
}
