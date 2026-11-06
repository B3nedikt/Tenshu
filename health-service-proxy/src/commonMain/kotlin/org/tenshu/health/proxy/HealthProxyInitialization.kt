package org.tenshu.health.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.Projection
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.LatestSample
import org.tenshu.core.health.Workout
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.health.WorkoutRollupAttribution
import org.tenshu.core.health.WorkoutRollupState
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.sync.common.TombstoneEmitterHolder

/**
 * Initialises the health service's proxy-side observers.
 *
 * Each stored entity family's [HealthService.Relations] list is a derived view of every
 * locally-known entity. Relation membership is not a synced primitive — `SyncBlock`s carry the
 * entity, and each receiver derives the relation locally. Without this, an entity arriving via
 * sync lands in the local DB but stays invisible to its stream.
 *
 * The seven membership relations (workouts / dailyActivities / dailyNutritions /
 * menstruationDays / sleepSessions / healthSamples / medicalRecords) are **declared on
 * [HealthService.Schema]** (`serviceDataRelation(id, type, maintained = true)`) and served by the single
 * generic `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) — they were
 * seven near-identical hand-written observers. Idempotent via remove-then-add keyed on each
 * entity's own `DataId` (workout id / activity date), so the explicit `saveData` (importing
 * device) and the sync-merge path (every other device) converge to a single entry per key.
 *
 * ⚠ [LatestSampleMaintainer] is **not** one of them and stays hand-written: it is a
 * rollup/aggregate (latest per `(metric, resting)`, newer-wins), not membership — §9.2's
 * "deliberate exclusions".
 */
fun ExecutionContext.initHealthService(
    tombstoneEmitterHolder: TombstoneEmitterHolder? = null
) {
    installRelationMaintainer()

    // The import-run retention cap (`fit-data-sources-restructure.md` D8). A run log is the one
    // health relation that grows on a *schedule* rather than with the user's data, so it is the
    // one that needs a ceiling.
    addDataObserver(ImportRunRetentionObserver(this, tombstoneEmitterHolder))

    // The latest-per-metric rollup (fit-read-scaling.md Ph3/4): maintained on every
    // HealthSample save — local writes AND sync-arriving rows both pass through the
    // observer — plus a one-shot derive for stores that already held samples before
    // the rollup shipped.
    val latestSampleMaintainer = LatestSampleMaintainer(this)
    addDataObserver(latestSampleMaintainer)
    latestSampleMaintainer.backfillIfEmpty()

    // The per-activity workout rollup (fit-read-scaling.md Ph3/4). Same shape as the
    // latest-per-metric rollup above, and the same reason: counting by grouping the whole
    // workout history is the read that stopped completing after a full history import.
    val workoutActivityRollupMaintainer = WorkoutActivityRollupMaintainer(this)
    addDataObserver(workoutActivityRollupMaintainer)
    workoutActivityRollupMaintainer.deriveIfStale()
}

/**
 * Enforces [HealthImportRun.RETENTION_CAP] per (kind, device) — the
 * `FileHistoryPolicyObserver` shape (`fit-data-sources-restructure.md` D8).
 *
 * ⚠⚠ **Why a run log needs a cap when no other health relation does.** Workouts, samples and
 * sleep sessions grow with the user's *data*; import runs grow with the *clock*. Fit's
 * `HealthImportBootstrap` fires on every foreground, so without a ceiling this
 * `Syncable(Eager)` relation becomes a heartbeat broadcast into the realm — the shape that once
 * made a derived projection 99.8 % of all sync traffic. The client-side rule that a no-op
 * refresh writes no run at all is the first line of defence; this is the second, and it is the
 * one that survives a caller getting it wrong.
 *
 * ⚠ Only `USER`-origin saves trim. Peers drop the same rows via the propagated tombstone; each
 * re-trimming on ingest would double the tombstone traffic for no convergence benefit.
 *
 * The trim is `executionContext.deleteData(...)`, which fans out to every observer — including
 * the generic `RelationMaintainer`, which drops the row from `IMPORT_RUNS`. This observer never
 * touches the array itself; it only decides which rows die.
 */
private class ImportRunRetentionObserver(
    private val executionContext: ExecutionContext,
    private val tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
) : DataObserver {

    // Read-only — the generic maintainer owns writing this array. Derived from the relation's
    // declared spec so the two cannot disagree about isEntity / elementSchemaId.
    private val importRunsRef: ArrayDatabaseReference by lazy {
        executionContext.relationReference(HealthService.Relations.IMPORT_RUNS)
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isImportRun()) return
        if (origin != WriteOrigin.USER) return
        val kind = stringFieldOf(data, HealthImportRun.Fields.KIND) ?: return
        val deviceId = stringFieldOf(data, HealthImportRun.Fields.DEVICE_ID) ?: return
        enforceRetention(kind, deviceId)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Nothing to cascade: a run owns no other row.
    }

    /**
     * Trim one (kind, device) group down to [HealthImportRun.RETENTION_CAP], oldest first.
     *
     * ⚠ The group is (kind, device), not the whole relation. Trimming globally would let a
     * device that imports hourly evict another device's entire history — and the other device
     * could not restore it, because it converges on the tombstone.
     */
    private fun enforceRetention(kind: String, deviceId: String) {
        val group = (executionContext.database.getArrayData(importRunsRef)?.toList() ?: return)
            .filter {
                stringFieldOf(it, HealthImportRun.Fields.KIND) == kind &&
                    stringFieldOf(it, HealthImportRun.Fields.DEVICE_ID) == deviceId
            }
        var overBy = group.size - HealthImportRun.RETENTION_CAP
        if (overBy <= 0) return

        for (row in group.sortedBy { longFieldOf(it, HealthImportRun.Fields.STARTED_AT) ?: 0L }) {
            if (overBy <= 0) break
            val key = stringFieldOf(row, HealthImportRun.Fields.KEY) ?: continue
            val rowId = DataId.StringDataId.create(
                context = executionContext.context,
                type = HealthImportRun.ID,
                keyValue = key
            )
            executionContext.deleteData(rowId, origin = WriteOrigin.USER)
            // ⚠⚠ The id form — see EI-1; a locally-hashed target matches nothing on any peer.
            tombstoneEmitterHolder?.emitter?.emit(
                targetEntityId = rowId,
                targetEntityType = HealthImportRun.ID,
            )
            overBy--
        }
    }

    private fun Data.isImportRun(): Boolean {
        val typeId = (this as? Entity<*>)?.type?.get() ?: this.id
        val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            ?: return false
        return schema.isSubtypeOf(HealthImportRun.ID)
    }

    private fun stringFieldOf(data: Data, fieldRef: String): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldRef)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(fieldRef).get() }.getOrNull()
        else -> null
    }

    private fun longFieldOf(data: Data, fieldRef: String): Long? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TInteger>(fieldRef)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TInteger>(fieldRef).get() }.getOrNull()
        else -> null
    }
}

/**
 * Re-derives the [WorkoutActivityRollup] counts from the stored workouts when they are missing or
 * were produced by an older [WorkoutActivityRollup.ROLLUP_GENERATION] — the upgrade *and* repair
 * path. [initHealthService] runs it at proxy init; public (and idempotent) so tests can drive it
 * directly.
 */
fun ExecutionContext.backfillWorkoutActivityRollups() {
    WorkoutActivityRollupMaintainer(this).deriveIfStale()
}

/**
 * Re-derives the [LatestSample] rollup from the stored sample time-series **iff the rollup is
 * empty** — the upgrade path for a store that held samples before the rollup shipped.
 * [initHealthService] runs it once at proxy init; public (and idempotent) so tests can drive
 * the pre-rollup-store path directly.
 */
fun ExecutionContext.backfillLatestSamples() {
    LatestSampleMaintainer(this).backfillIfEmpty()
}

/**
 * Maintains the [LatestSample] latest-per-metric rollup (`fit-read-scaling.md` Ph3/4): on every
 * [HealthSample] save — the importing device's explicit write AND rows arriving via sync both
 * pass through here — the `(metric, resting)` rollup row is overwritten iff the incoming
 * reading is at least as new as the stored one, so out-of-order arrivals (a historical import
 * after live captures) never regress the teaser. Rows are written straight through
 * [org.tenshu.core.common.proxy.Database.saveData] (no observer dispatch, no sync emission):
 * the rollup is locally derived on every device, never a synced primitive — see [LatestSample].
 *
 * Deletion is deliberately not handled: removing the sample that *was* the latest leaves the
 * rollup stale until that metric's next write (documented on [LatestSample]; recomputing would
 * need the very scan this rollup removes).
 */
private class LatestSampleMaintainer(
    private val executionContext: ExecutionContext
) : DataObserver {

    // ⚠ Hand-built, and it has to be: LATEST_SAMPLES declares no `order`
    // (it is a rollup, not membership), so it has no `RelationSpec` for
    // `relationReference` to derive from. This observer is the array's only
    // writer as well as its only reader, so there is nothing to drift from.
    private val latestSamplesRef: ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = HealthService.Relations.LATEST_SAMPLES
        ),
        isEntity = false,
        elementSchemaId = LatestSample.ID
    )

    // The sample time-series, on the other hand, IS a declared relation the
    // generic maintainer writes — so derive the reference from its spec rather
    // than restating `isEntity`/`elementSchemaId` here (D4).
    private val healthSamplesRef: ArrayDatabaseReference =
        executionContext.relationReference(HealthService.Relations.HEALTH_SAMPLES)

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isHealthSample()) return
        val reading = readingOf(data) ?: return
        upsertIfNewer(reading)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Stale-until-next-write by design — see the class KDoc.
    }

    /**
     * One-shot derive for stores that already held samples before the rollup shipped: when the
     * rollup relation is empty but samples exist, scan the time-series once and seed the rows.
     * The emptiness probe is member-hashes only (cheap); the O(N) scan runs at most once per
     * store lifetime. A fresh store syncing from scratch never scans — samples arrive through
     * [onDataSaved] and build the rollup incrementally.
     */
    fun backfillIfEmpty() {
        val existing = executionContext.database.getArrayMemberHashes(latestSamplesRef)
        if (!existing.isNullOrEmpty()) return

        val samples = executionContext.database.getArrayData(healthSamplesRef, null)
            ?: return
        val best = mutableMapOf<String, Reading>()
        samples.forEach { sample ->
            val reading = readingOf(sample) ?: return@forEach
            val key = LatestSample.keyFor(reading.metric, reading.resting)
            val current = best[key]
            if (current == null || reading.timeMs >= current.timeMs) best[key] = reading
        }
        best.values.forEach { write(it) }
    }

    private fun upsertIfNewer(reading: Reading) {
        val key = LatestSample.keyFor(reading.metric, reading.resting)
        val latestId = DataId.StringDataId.create(
            context = executionContext.context,
            type = LatestSample.ID,
            keyValue = key
        )
        val storedTimeMs = executionContext.database.getData(latestId, asEntity = false)
            ?.let { stored ->
                runCatching {
                    (stored as Composite).getField<UnixInstant>(LatestSample.Fields.TIME)
                        .toEpochMilliseconds()
                }.getOrNull()
            }
        if (storedTimeMs != null && storedTimeMs > reading.timeMs) return
        write(reading)
    }

    private fun write(reading: Reading) {
        val row = LatestSample.create(
            context = executionContext.context,
            metric = reading.metric,
            value = reading.value,
            unit = reading.unit,
            time = reading.time,
            source = reading.source,
            resting = reading.resting,
        )
        val latestId = DataId.StringDataId.create(
            context = executionContext.context,
            type = LatestSample.ID,
            keyValue = LatestSample.keyFor(reading.metric, reading.resting)
        )
        executionContext.database.transaction {
            executionContext.database.saveData(row)
            executionContext.database.removeFromArray(latestSamplesRef, latestId)
            executionContext.database.addToArray(latestSamplesRef, row)
        }
    }

    private class Reading(
        val metric: String,
        val value: String,
        val unit: String,
        val time: UnixInstant,
        val source: String,
        val resting: Boolean,
    ) {
        val timeMs: Long get() = time.toEpochMilliseconds()
    }

    private fun readingOf(data: Data): Reading? = runCatching {
        when (data) {
            is Entity<*> -> Reading(
                metric = data.getFieldValue<TString>(HealthSample.Fields.METRIC).get(),
                value = data.getFieldValue<TString>(HealthSample.Fields.VALUE).get(),
                unit = data.getFieldValue<TString>(HealthSample.Fields.UNIT).get(),
                time = data.getFieldValue(HealthSample.Fields.TIME),
                source = data.getFieldValue<TString>(HealthSample.Fields.SOURCE).get(),
                resting = data.getFieldValue<TBoolean>(HealthSample.Fields.RESTING).get(),
            )
            is Composite -> Reading(
                metric = data.getField<TString>(HealthSample.Fields.METRIC).get(),
                value = data.getField<TString>(HealthSample.Fields.VALUE).get(),
                unit = data.getField<TString>(HealthSample.Fields.UNIT).get(),
                time = data.getField(HealthSample.Fields.TIME),
                source = data.getField<TString>(HealthSample.Fields.SOURCE).get(),
                resting = data.getField<TBoolean>(HealthSample.Fields.RESTING).get(),
            )
            else -> null
        }
    }.getOrNull()

    private fun Data.isHealthSample(): Boolean {
        val typeId = (this as? Entity<*>)?.type?.get() ?: this.id
        val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            ?: return false
        return schema.isSubtypeOf(HealthSample.ID)
    }
}

/**
 * Maintains the [WorkoutActivityRollup] per-activity counts (`fit-read-scaling.md` Ph3/4, the
 * [LatestSampleMaintainer] pattern): every [Workout] save — the importing device's write AND rows
 * arriving via sync both pass through here — adjusts exactly one count by exactly one.
 *
 * ⚠⚠ **A count is not a max, so it cannot be a blind increment.** The same workout is re-saved on
 * every sync re-delivery and on any re-run of an import; incrementing on each would inflate the
 * counts without bound, and nothing downstream could tell. [WorkoutRollupAttribution] — one tiny
 * row per workout recording the type it was last counted under — is what makes the three cases
 * distinguishable in O(1), with no rescan:
 *
 * | attribution | meaning | action |
 * |---|---|---|
 * | absent | first sight of this workout | increment its type |
 * | same type | re-save (sync re-delivery, re-import) | **no-op** |
 * | different type | type corrected on re-import | decrement old, increment new |
 *
 * Deletes decrement the recorded bucket and drop the attribution, so a `DeleteRealmData` reset of
 * `Workout` leaves the rollup at zero rather than advertising counts for rows that are gone —
 * the one place this maintainer is deliberately stricter than [LatestSampleMaintainer], whose
 * stale-on-delete limit is tolerable only because samples have no delete surface.
 *
 * Rows are written straight through [org.tenshu.core.common.proxy.Database.saveData] (no observer
 * dispatch, no sync emission): the rollup is locally derived on every device, never synced.
 */
private class WorkoutActivityRollupMaintainer(
    private val executionContext: ExecutionContext
) : DataObserver {

    private val rollupsRef: ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = HealthService.Relations.WORKOUT_ACTIVITY_ROLLUPS
        ),
        isEntity = false,
        elementSchemaId = WorkoutActivityRollup.ID
    )

    private val workoutsRef: ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = HealthService.Relations.WORKOUTS
        ),
        isEntity = false,
        elementSchemaId = Workout.ID
    )

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isWorkout()) return
        val workoutId = workoutIdOf(data) ?: return
        val activityType = activityTypeOf(data) ?: return

        // ⚠⚠ The count and its attribution MUST commit together. They are the two halves of one
        // fact ("this workout is counted, under this type"), and the failure is silent and
        // permanent: a committed increment whose attribution never landed leaves the workout
        // counted but unattributed, so its next save reads "never counted" and increments a second
        // time. Observed on device — 625 of 10 517 workouts had drifted this way — because the
        // rollup write was transactional while the attribution was a bare `saveData` left sitting
        // in the store's write mailbox when the process was killed. One transaction makes the pair
        // all-or-nothing; "neither" is self-correcting, since a re-save then counts it exactly once.
        when (val attributed = attributionOf(workoutId)) {
            null -> executionContext.database.transaction {
                adjust(activityType, +1)
                writeAttribution(workoutId, activityType)
            }
            activityType -> Unit // Re-save of a workout already counted — see the class KDoc.
            else -> executionContext.database.transaction {
                adjust(attributed, -1)
                adjust(activityType, +1)
                writeAttribution(workoutId, activityType)
            }
        }
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val stringDataId = dataId as? DataId.StringDataId ?: return
        if (stringDataId.type.get() != Workout.ID) return
        val workoutId = stringDataId.keyValue.get()
        val attributed = attributionOf(workoutId) ?: return
        executionContext.database.transaction {
            adjust(attributed, -1)
            executionContext.database.deleteData(attributionId(workoutId))
        }
    }

    /**
     * Derive the rollup from the stored workouts when it is missing **or stale**.
     *
     * Two triggers, both one-shot:
     * - the rollup relation is empty (a store that held workouts before the rollup shipped), or
     * - the stored [WorkoutRollupState] generation is behind [WorkoutActivityRollup.ROLLUP_GENERATION]
     *   — the repair lever. A derived view keeps whatever *past* code wrote, so fixing the
     *   maintainer does not fix the rows already on disk; bumping the generation is what does.
     *
     * The scan projects only the two fields it needs, so it never materialises the workout rows
     * that made the unbounded read collapse in the first place. A fresh store syncing from scratch
     * pays a scan over an empty relation and then builds incrementally through [onDataSaved].
     */
    fun deriveIfStale() {
        val rollupsPresent =
            !executionContext.database.getArrayMemberHashes(rollupsRef).isNullOrEmpty()
        if (rollupsPresent && storedGeneration() == WorkoutActivityRollup.ROLLUP_GENERATION) return

        val workouts = executionContext.database.getArrayData(
            workoutsRef,
            Projection(
                keepFields = setOf(Workout.Fields.WORKOUT_ID, Workout.Fields.ACTIVITY_TYPE),
                skipUnprojectedComposites = true,
            ),
        )

        val counts = mutableMapOf<String, Long>()
        val attributions = mutableListOf<Pair<String, String>>()
        workouts?.forEach { workout ->
            val workoutId = workoutIdOf(workout) ?: return@forEach
            val activityType = activityTypeOf(workout) ?: return@forEach
            counts[activityType] = (counts[activityType] ?: 0L) + 1L
            attributions += workoutId to activityType
        }

        executionContext.database.transaction {
            // Drop the previous rows first: a re-derive must REPLACE, not merge. A type whose
            // workouts have all gone would otherwise keep its stale count forever, since the scan
            // simply never mentions it.
            clearRollupRows()
            counts.forEach { (activityType, count) -> writeRollup(activityType, count) }
            // Rewriting every attribution is what repairs a drifted store: any that were lost are
            // restored, and the rest are overwritten with the same value.
            attributions.forEach { (workoutId, activityType) ->
                writeAttribution(workoutId, activityType)
            }
            stampGeneration()
        }
    }

    /** Removes every rollup row and its relation entry, leaving the relation empty. */
    private fun clearRollupRows() {
        val existing = executionContext.database.getArrayData(rollupsRef, null) ?: return
        existing.toList().forEach { row ->
            val activityType = stringFieldOf(row, WorkoutActivityRollup.Fields.ACTIVITY_TYPE)
                ?: return@forEach
            val id = rollupId(activityType)
            executionContext.database.removeFromArray(rollupsRef, id)
            executionContext.database.deleteData(id)
        }
    }

    private fun storedGeneration(): Long? =
        executionContext.database.getData(stateId(), asEntity = false)
            ?.let { stored ->
                runCatching {
                    (stored as Composite)
                        .getField<TInteger>(WorkoutRollupState.Fields.GENERATION).get()
                }.getOrNull()
            }

    private fun stampGeneration() {
        executionContext.database.saveData(
            WorkoutRollupState.create(
                context = executionContext.context,
                generation = WorkoutActivityRollup.ROLLUP_GENERATION,
            )
        )
    }

    private fun stateId(): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = WorkoutRollupState.ID,
            keyValue = WorkoutRollupState.SINGLETON_KEY
        )

    /** Shifts one activity's count by [delta], clamped at zero, and writes the row. */
    private fun adjust(activityType: String, delta: Long) {
        val current = storedCount(activityType) ?: 0L
        val next = (current + delta).coerceAtLeast(0L)
        writeRollup(activityType, next)
    }

    private fun storedCount(activityType: String): Long? =
        executionContext.database.getData(rollupId(activityType), asEntity = false)
            ?.let { stored ->
                runCatching {
                    (stored as Composite)
                        .getField<TInteger>(WorkoutActivityRollup.Fields.COUNT).get()
                }.getOrNull()
            }

    /**
     * Writes one rollup row. Deliberately **not** transactional in itself: every caller already
     * runs inside a transaction that also carries the matching attribution, and nesting a
     * transaction inside that pairing is exactly what let the two halves commit separately.
     */
    private fun writeRollup(activityType: String, count: Long) {
        val row = WorkoutActivityRollup.create(
            context = executionContext.context,
            activityType = activityType,
            count = count,
        )
        val id = rollupId(activityType)
        executionContext.database.saveData(row)
        executionContext.database.removeFromArray(rollupsRef, id)
        executionContext.database.addToArray(rollupsRef, row)
    }

    private fun writeAttribution(workoutId: String, activityType: String) {
        executionContext.database.saveData(
            WorkoutRollupAttribution.create(
                context = executionContext.context,
                workoutId = workoutId,
                activityType = activityType,
            )
        )
    }

    /** The type this workout is currently counted under, or `null` if never counted. */
    private fun attributionOf(workoutId: String): String? =
        executionContext.database.getData(attributionId(workoutId), asEntity = false)
            ?.let { stored ->
                runCatching {
                    (stored as Composite)
                        .getField<TString>(WorkoutRollupAttribution.Fields.ACTIVITY_TYPE).get()
                }.getOrNull()
            }

    private fun rollupId(activityType: String): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = WorkoutActivityRollup.ID,
            keyValue = activityType
        )

    private fun attributionId(workoutId: String): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = WorkoutRollupAttribution.ID,
            keyValue = workoutId
        )

    private fun Data.isWorkout(): Boolean {
        val id = (this as? Entity<*>)?.type?.get() ?: this.id
        val schema = executionContext.schemaProvider.getSchema(id) as? CompositeSchema
            ?: return false
        return schema.isSubtypeOf(Workout.ID)
    }

    private fun workoutIdOf(data: Data): String? = stringFieldOf(data, Workout.Fields.WORKOUT_ID)

    private fun activityTypeOf(data: Data): String? =
        stringFieldOf(data, Workout.Fields.ACTIVITY_TYPE)

    private fun stringFieldOf(data: Data, fieldRef: String): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldRef)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(fieldRef).get() }.getOrNull()
        else -> null
    }
}
