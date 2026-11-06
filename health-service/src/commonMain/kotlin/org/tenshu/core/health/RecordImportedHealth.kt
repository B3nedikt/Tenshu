package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.number.TInteger
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records (upserts) a whole imported-health batch — every summary record of an import in **one**
 * operation round-trip, instead of one op per record.
 *
 * Motivation (fit-app.md §9.16): on iOS the co-resident Control is SIGKILLed seconds after Fit
 * comes to the foreground, so an import fired as N per-record EPC round-trips loses the race —
 * only the first few records land before the host dies. One batched call moves the entire payload
 * across while the channel is alive. Each record kind keeps its content-addressed identity
 * (`workoutId` / `date` / `metric` / `recordId`), so the batch inherits the per-record ops'
 * idempotence: a retried or re-run import folds to the same rows.
 *
 * Deliberately **summary-only**: a workout carrying a dense route/HR stream blob stays on
 * [recordWorkout] (one blob per op frame) — batching many stream blobs into a single frame would
 * trade the timeout race for a payload memory ceiling.
 *
 * All seven params are required on the wire; an empty list is the "none of this kind" encoding
 * (no optional-param presence handling, no empty-vs-absent ambiguity).
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordImportedHealth(
    workouts: List<Workout> = emptyList(),
    dailyActivities: List<DailyActivity> = emptyList(),
    dailyNutritions: List<DailyNutrition> = emptyList(),
    menstruationDays: List<MenstruationDay> = emptyList(),
    sleepSessions: List<SleepSession> = emptyList(),
    healthSamples: List<HealthSample> = emptyList(),
    medicalRecords: List<MedicalRecord> = emptyList(),
): TResult<TInteger> =
    findOperation<RecordImportedHealth>(RecordImportedHealth.ID)
        .recordImportedHealth(workouts, dailyActivities, dailyNutritions, menstruationDays, sleepSessions, healthSamples, medicalRecords)
        .asTaskInteractor<TInteger>()
        .execute()

interface RecordImportedHealth : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordImportedHealth(
        workouts: List<Workout>,
        dailyActivities: List<DailyActivity>,
        dailyNutritions: List<DailyNutrition>,
        menstruationDays: List<MenstruationDay>,
        sleepSessions: List<SleepSession>,
        healthSamples: List<HealthSample>,
        medicalRecords: List<MedicalRecord>,
    ): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordImportedHealth"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                list(id = Params.WORKOUTS, of = Workout.Schema)
                list(id = Params.DAILY_ACTIVITIES, of = DailyActivity.Schema)
                list(id = Params.DAILY_NUTRITIONS, of = DailyNutrition.Schema)
                list(id = Params.MENSTRUATION_DAYS, of = MenstruationDay.Schema)
                list(id = Params.SLEEP_SESSIONS, of = SleepSession.Schema)
                list(id = Params.HEALTH_SAMPLES, of = HealthSample.Schema)
                list(id = Params.MEDICAL_RECORDS, of = MedicalRecord.Schema)
            },
            // ⚠⚠ Returns how many rows were **new**, not how many were offered. Every key here
            // is a stable source id, so re-reading an unchanged window folds onto the same rows
            // and adds nothing — and a caller that recorded what it *read* filled the import log
            // with identical entries on every foreground refresh. The op is the only thing that
            // can answer this: it is where the store is, and it already had to look.
            //
            // Same shape as `DeleteRealmData`, for the same reason it gives: report what
            // actually happened rather than let the caller guess beforehand.
            returnType = TInteger.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val WORKOUTS = "$ID/workouts"
        const val DAILY_ACTIVITIES = "$ID/dailyActivities"
        const val DAILY_NUTRITIONS = "$ID/dailyNutritions"
        const val MENSTRUATION_DAYS = "$ID/menstruationDays"
        const val SLEEP_SESSIONS = "$ID/sleepSessions"
        const val HEALTH_SAMPLES = "$ID/healthSamples"
        const val MEDICAL_RECORDS = "$ID/medicalRecords"
    }
}
