package app.tenshu.fit.data

import org.tenshu.core.TResult
import org.tenshu.core.number.TInteger
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.core.TContext
import org.tenshu.core.orThrow
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordImportedHealth
import org.tenshu.core.health.recordWorkout
import org.tenshu.health.client.healthService
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.health.common.WorkoutStreamsCodec

/**
 * The **write side of the health-import seam** — maps the neutral [ImportedHealth] a
 * [HealthImporter] acquired onto the `:health-service` schema and persists it. Shared across every
 * importer (Health Connect / HealthKit / file / [ReferenceHealthImporter]) — only *acquisition* is
 * platform-specific.
 *
 * Summary records ride the **batched `recordImportedHealth` op** — the whole payload in one
 * operation round-trip (chunked only when a bulk import exceeds [BATCH_LIMIT] records), because on
 * iOS the co-resident Control is SIGKILLed seconds after Fit foregrounds and a per-record op
 * stream loses that race (fit-app.md §9.16). Workouts carrying a dense route/HR stream blob stay
 * on per-record `recordWorkout` — one blob per op frame, never many blobs in one payload.
 *
 * Idempotent: every record is content-addressed (by `workoutId` / `date` / `metric` /
 * `recordId`), so re-importing folds to one row rather than duplicating.
 */
class HealthImportWriter(
    private val client: TenshuClient,
    private val context: TContext,
    /**
     * Runs before **every** op call — each batch, chunk and dense workout — and throws to stop the
     * write there (`ADR 0025` §8.6: the realm gate is observed for the whole run, not sampled once at
     * entry). Batches already written stay written; the rest is never attempted. ⚠ Required, never
     * defaulted: pass the realm gate, or `OPEN_REALM_GATE` where the wall already covers the write.
     */
    private val beforeEachWrite: suspend () -> Unit,
) {
    /**
     * Persists [imported]. [onRecord] runs after **each** record lands — the caller uses it to report
     * progress and to apply backpressure (a bulk Takeout import writes tens of thousands of records,
     * and firing them at the proxy faster than it can flush + GC blows the proxy's heap; the caller
     * paces on this hook). It suspends, so a `delay` there throttles the whole write. With batching
     * the hook fires once per record *after its chunk lands*, so progress counts stay per-record and
     * the pacer's pauses aggregate between chunk ops instead of between single-record ops.
     *
     * No per-item success logging: the streaming import calls write() once per file — thousands of
     * times — and a log line per call floods logcat (it overran the ring buffer, hiding the real
     * events). Failures still log with the kind + index; the aggregate outcome is logged once by the
     * caller. Kind + index only, never a value — these are GDPR Art. 9 rows (A34).
     */
    /**
     * @return how many rows were **new** — the figure the import log records.
     *
     * ⚠⚠ Not the number offered. Every health key is a stable *source* id (Health Connect's own
     * record id, a calendar date, `"metric::timeMs"`), so re-reading an unchanged window folds
     * onto the same rows and adds nothing. Recording what was read instead is what put four
     * identical "164 items" entries in a real device's history inside fifteen minutes.
     *
     * ⚠ Dense workouts are counted as new unconditionally: they go through `recordWorkout`,
     * which reports nothing, and they are the small minority that carry route/HR streams.
     */
    suspend fun write(imported: ImportedHealth, onRecord: suspend () -> Unit = {}): Int {
        if (imported.isEmpty) return 0

        // Dense route/HR blobs stay per-record (see class KDoc); everything else batches.
        val (denseWorkouts, summaryWorkouts) = imported.workouts.partition {
            it.route.isNotEmpty() || it.heartRate.isNotEmpty()
        }

        var added = writeBatched(
            workouts = summaryWorkouts.map { createWorkout(it) },
            dailyActivities = imported.dailyActivities.map { d ->
                DailyActivity.create(
                    context = context,
                    date = d.date,
                    steps = d.steps,
                    distanceM = d.distanceM,
                    activeEnergyKj = d.activeEnergyKj,
                    activeMinutes = d.activeMinutes,
                )
            },
            dailyNutritions = imported.dailyNutritions.map { n ->
                DailyNutrition.create(
                    context = context,
                    date = n.date,
                    energyKj = n.energyKj,
                    hydrationMl = n.hydrationMl,
                )
            },
            menstruationDays = imported.menstruationDays.map { m ->
                MenstruationDay.create(
                    context = context,
                    date = m.date,
                    flow = m.flow,
                    periodStart = m.periodStart,
                )
            },
            sleepSessions = imported.sleepSessions.map { s ->
                SleepSession.create(
                    context = context,
                    date = s.date,
                    start = UnixInstantDto(s.startMs),
                    end = UnixInstantDto(s.endMs),
                    durationSec = s.durationSec,
                    source = s.source,
                    awakeMin = s.awakeMin,
                    remMin = s.remMin,
                    lightMin = s.lightMin,
                    deepMin = s.deepMin,
                )
            },
            healthSamples = imported.samples.map { s ->
                HealthSample.create(
                    context = context,
                    metric = s.metric,
                    value = s.value,
                    unit = s.unit,
                    time = UnixInstantDto(s.timeMs),
                    source = s.source,
                    resting = s.resting,
                )
            },
            medicalRecords = imported.medicalRecords.map { m ->
                MedicalRecord.create(
                    context = context,
                    recordId = m.recordId,
                    kind = m.kind,
                    title = m.title,
                    detail = m.detail,
                    effectiveDate = m.effectiveDate,
                    issuer = m.issuer,
                    source = m.source,
                )
            },
            onRecord = onRecord,
        )

        added += denseWorkouts.size
        writeEach("workout", denseWorkouts, onRecord) { w ->
            // High-fidelity imports (a full GPX track / FIT file) carry the dense route + HR
            // streams → store them on Workout.Streams; this partition is never empty-streamed.
            val streams = WorkoutStreams(route = w.route, heartRate = w.heartRate)
            client.healthService.recordWorkout(
                createWorkout(w),
                streams = TByteArrayDto(WorkoutStreamsCodec.encode(streams)),
            )
        }
        return added
    }

    private fun createWorkout(w: ImportedWorkout): Workout = Workout.create(
        context = context,
        workoutId = w.workoutId,
        activityType = w.activityType,
        source = w.source,
        start = UnixInstantDto(w.startMs),
        end = UnixInstantDto(w.endMs),
        durationSec = w.durationSec,
        title = w.title,
        distanceM = w.distanceM,
        activeEnergyKj = w.activeEnergyKj,
        avgHr = w.avgHr,
        steps = w.steps,
    )

    /**
     * Persists the mapped summary records through `recordImportedHealth`. The common case — an
     * import (or one streamed file's worth) of at most [BATCH_LIMIT] records — is **one** op call
     * carrying all five lists. A larger bulk import falls back to per-kind chunks so no single op
     * payload grows unbounded; extra round-trips are irrelevant at that scale (desktop/Android
     * bulk paths, not the iOS race).
     */
    private suspend fun writeBatched(
        workouts: List<Workout>,
        dailyActivities: List<DailyActivity>,
        dailyNutritions: List<DailyNutrition>,
        menstruationDays: List<MenstruationDay>,
        sleepSessions: List<SleepSession>,
        healthSamples: List<HealthSample>,
        medicalRecords: List<MedicalRecord>,
        onRecord: suspend () -> Unit,
    ): Int {
        val total = workouts.size + dailyActivities.size + dailyNutritions.size + menstruationDays.size +
            sleepSessions.size + healthSamples.size + medicalRecords.size
        if (total == 0) return 0

        if (total <= BATCH_LIMIT) {
            val added = recordBatch("batch ($total records)") {
                client.healthService.recordImportedHealth(
                    workouts = workouts,
                    dailyActivities = dailyActivities,
                    dailyNutritions = dailyNutritions,
                    menstruationDays = menstruationDays,
                    sleepSessions = sleepSessions,
                    healthSamples = healthSamples,
                    medicalRecords = medicalRecords,
                )
            }
            repeat(total) { onRecord() }
            return added
        }

        var added = 0
        added += writeChunked("dailyActivity", dailyActivities, onRecord) { chunk ->
            client.healthService.recordImportedHealth(dailyActivities = chunk)
        }
        added += writeChunked("dailyNutrition", dailyNutritions, onRecord) { chunk ->
            client.healthService.recordImportedHealth(dailyNutritions = chunk)
        }
        added += writeChunked("menstruationDay", menstruationDays, onRecord) { chunk ->
            client.healthService.recordImportedHealth(menstruationDays = chunk)
        }
        added += writeChunked("sleepSession", sleepSessions, onRecord) { chunk ->
            client.healthService.recordImportedHealth(sleepSessions = chunk)
        }
        added += writeChunked("healthSample", healthSamples, onRecord) { chunk ->
            client.healthService.recordImportedHealth(healthSamples = chunk)
        }
        added += writeChunked("medicalRecord", medicalRecords, onRecord) { chunk ->
            client.healthService.recordImportedHealth(medicalRecords = chunk)
        }
        added += writeChunked("workout", workouts, onRecord) { chunk ->
            client.healthService.recordImportedHealth(workouts = chunk)
        }
        return added
    }

    private suspend fun <T> writeChunked(
        kind: String,
        items: List<T>,
        onRecord: suspend () -> Unit,
        record: suspend (List<T>) -> TResult<*>,
    ): Int {
        var added = 0
        items.chunked(BATCH_LIMIT).forEach { chunk ->
            added += recordBatch("$kind chunk (${chunk.size} records)") { record(chunk) }
            repeat(chunk.size) { onRecord() }
        }
        return added
    }

    /**
     * @return how many rows the proxy reported as **new**.
     *
     * ⚠ An op that answers with something other than a count reports 0 rather than guessing the
     * batch size. Under-reporting a run is recoverable; claiming rows were added when the proxy
     * never said so is the fabrication this whole figure exists to remove.
     */
    private suspend fun recordBatch(label: String, record: suspend () -> TResult<*>): Int {
        beforeEachWrite()
        try {
            val result = record()
            result.orThrow("persisting $label")
            return ((result as? TResult.Success)?.value as? TInteger)?.get()?.toInt() ?: 0
        } catch (e: Throwable) {
            fitLog("write: FAILED persisting $label — aborting the import", e)
            throw e
        }
    }

    /**
     * Persists [items] one by one (running [onRecord] after each), naming the kind + position of a
     * failure. [record] returns the op's [TResult] so a `TResult.Failure` (e.g. "Proxy not
     * connected" — the executor folds transport failures into a Failure instead of throwing)
     * aborts the import loudly rather than reporting success with nothing written (tech-debt
     * "`TResult`-returning ops are silently discardable"; the callers' catch → `ImportStatus.Failed`
     * surfaces it).
     */
    private suspend fun <T> writeEach(
        kind: String,
        items: List<T>,
        onRecord: suspend () -> Unit,
        record: suspend (T) -> TResult<*>,
    ) {
        items.forEachIndexed { index, item ->
            beforeEachWrite()
            try {
                record(item).orThrow("persisting $kind ${index + 1}/${items.size}")
            } catch (e: Throwable) {
                fitLog("write: FAILED persisting $kind ${index + 1}/${items.size} — aborting the import", e)
                throw e
            }
            onRecord()
        }
    }

    private companion object {
        /**
         * Records per `recordImportedHealth` call. Big enough that every real phone import
         * (HealthKit / Health Connect refresh — a few hundred summary rows) is one round-trip;
         * small enough that a chunk's payload and its proxy-side save loop stay well-bounded.
         */
        const val BATCH_LIMIT = 500
    }
}
