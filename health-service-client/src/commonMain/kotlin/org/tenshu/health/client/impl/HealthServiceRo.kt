package org.tenshu.health.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.LatestSample
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.Workout
import org.tenshu.core.request.RequestChain

internal class HealthServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : HealthService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val workouts: TList<Workout>
        get() = serviceRelation(HealthService.Relations.WORKOUTS, Workout.Schema)

    override val dailyActivities: TList<DailyActivity>
        get() = serviceRelation(HealthService.Relations.DAILY_ACTIVITIES, DailyActivity.Schema)

    override val dailyNutritions: TList<DailyNutrition>
        get() = serviceRelation(HealthService.Relations.DAILY_NUTRITIONS, DailyNutrition.Schema)

    override val menstruationDays: TList<MenstruationDay>
        get() = serviceRelation(HealthService.Relations.MENSTRUATION_DAYS, MenstruationDay.Schema)

    override val sleepSessions: TList<SleepSession>
        get() = serviceRelation(HealthService.Relations.SLEEP_SESSIONS, SleepSession.Schema)

    override val healthSamples: TList<HealthSample>
        get() = serviceRelation(HealthService.Relations.HEALTH_SAMPLES, HealthSample.Schema)

    override val latestSamples: TList<LatestSample>
        get() = serviceRelation(HealthService.Relations.LATEST_SAMPLES, LatestSample.Schema)

    override val workoutActivityRollups: TList<WorkoutActivityRollup>
        get() = serviceRelation(
            HealthService.Relations.WORKOUT_ACTIVITY_ROLLUPS,
            WorkoutActivityRollup.Schema
        )

    override val medicalRecords: TList<MedicalRecord>
        get() = serviceRelation(HealthService.Relations.MEDICAL_RECORDS, MedicalRecord.Schema)

    override val sources: TList<HealthSource>
        get() = serviceRelation(HealthService.Relations.SOURCES, HealthSource.Schema)

    override val importRuns: TList<HealthImportRun>
        get() = serviceRelation(HealthService.Relations.IMPORT_RUNS, HealthImportRun.Schema)
}
