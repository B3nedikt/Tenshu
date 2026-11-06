package app.tenshu.sdk.internal

import kotlinx.coroutines.flow.MutableStateFlow
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.Workout
import org.tenshu.core.id
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.DailyActivityMerger
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory

/**
 * `:health-service` on web (`web-app-suite.md` Wave 2) — what makes a timeline `Activity` moment's
 * workout resolve (its `activityType`), and what `Fit`'s reads and writes dispatch to.
 *
 * ⚠⚠ **`initHealthService` is NOT run, and must not be.** Beside the relation maintainer the reads
 * need, it installs `ImportRunRetentionObserver`, `LatestSampleMaintainer` and
 * `WorkoutActivityRollupMaintainer` — the latter two **backfill at start** (`backfillIfEmpty` /
 * `deriveIfStale` scan the health tables and write rollup rows). That is background work, run at
 * every tab start over the whole sample and workout history.
 * ⚠ Both rollups are locally derived and **never synced** (`LatestSample` /
 * `WorkoutActivityRollup` carry no `Syncable` trait), so a tab would write its own local rows, not a
 * second copy of a phone's: the cost of running them is the start-time scan, not a second writer.
 * Only `installRelationMaintainer()` runs here, plus [WorkoutChanges] — a counter, not work.
 * `NoBackgroundWorkInATabTest` holds the rest out.
 *
 * ⚠ Consequence: `HealthService.workoutActivityRollups` / `latestSamples` are **empty** in a tab, and
 * no phone's copy will ever arrive. `Fit` (`fit-app.md` §Web scope 3) therefore reads
 * profile height / weight from a bounded per-metric `healthSamples` read and no rollup at all;
 * `NoBackgroundWorkInATabTest.fitWritesAndReadsDeriveNoRollupInATab` pins that nothing derives them.
 *
 * The factory is the native one, wired to the runtime's own file database and sync seams (read at
 * dispatch, after `SyncPlugin.initialize` has published them), and registered whole: a dispatch of
 * an unregistered op fails loudly. `Fit`'s writes and the contract tests' seeded `Workout`s go
 * through the real ops.
 */
internal object HealthPlugin : JsRuntimePlugin {
    override val id: String = "health"

    override val schemaConfigs = listOf(healthServiceCommonModule, healthServiceClientModule)

    override val mergers = listOf(DailyActivityMerger())

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            healthServiceProxyImplementationFactory(
                id,
                ctx.fileDatabase,
                syncBlockEmitterHolder = ctx.syncBlockEmitterHolder,
                // ⚠ Read per dispatch: the emitter is published by `SyncPlugin.initialize`. `null`
                // (no realm yet) makes `DeleteWorkout` device-local, the factory's documented posture.
                tombstoneEmitter = ctx.tombstoneEmitterHolder.emitter,
            )
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        executionContext.installRelationMaintainer()
        if (!executionContext.observerRegistry.any { it === WorkoutChanges }) {
            executionContext.addDataObserver(WorkoutChanges)
        }
    }
}

/**
 * A version that moves on every `Workout` save (local or sync-applied) and every `Workout` delete —
 * the signal `Timeline`'s feed re-reads its keyed workout lookups on (`TimelineBook.WorkoutHydrator`)
 * — plus which workouts moved, so a bump re-reads **those** ids and not the whole window.
 *
 * ⚠ A counter, not work: it runs inside the save and does nothing but bump a number and remember an
 * id, so it is not the background work this plugin keeps out of a tab.
 *
 * ⚠ Workout-typed deletes only: every bump re-runs one keyed read per `Activity` moment, so a
 * delete of any other type must not bump it.
 */
internal object WorkoutChanges : DataObserver {
    val version = MutableStateFlow(0L)

    /** `(version, workoutId)` for the last [LOG_SIZE] changes; a null id is "a workout, unnamed". */
    private val log = ArrayDeque<Pair<Long, String?>>()

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        if (typeId != Workout.ID) return
        val workoutId = runCatching {
            when (data) {
                is Entity<*> -> data.getFieldValue<TString>(Workout.Fields.WORKOUT_ID).get()
                is Composite -> data.getField<TString>(Workout.Fields.WORKOUT_ID).get()
                else -> null
            }
        }.getOrNull()
        record(workoutId)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        if (dataId.type.get() != Workout.ID) return
        // `WorkoutDataIdProvider` keys a workout by its `workoutId`, so the key IS the id.
        record((dataId as? DataId.StringDataId)?.keyValue?.get())
    }

    /**
     * The workout ids changed after version [since], or **null** — "re-read everything" — when one
     * change named no id or the log no longer reaches back that far.
     */
    fun changedSince(since: Long): Set<String>? {
        val current = version.value
        if (since >= current) return emptySet()
        val newer = log.filter { it.first > since }
        if (newer.size.toLong() != current - since) return null
        return newer.map { it.second ?: return null }.toSet()
    }

    private fun record(workoutId: String?) {
        val next = version.value + 1
        log.addLast(next to workoutId)
        while (log.size > LOG_SIZE) log.removeFirst()
        version.value = next
    }

    private const val LOG_SIZE = 256
}
