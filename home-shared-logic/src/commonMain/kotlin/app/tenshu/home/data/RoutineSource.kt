package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Plain-model projection of one synced routine — decoupled from the schema types
 * so the repository and its tests never touch a `TContext` (the
 * [DeviceSource]/[SyncedDeviceInfo] pattern).
 */
/**
 * One firing, as the Activity feed needs it (`routines-engine.md` RE-1).
 *
 * ⭐ **The receipt and the history row are the same row.** `RoutineRun` carries its own outcome, so
 * the feed reads the engine's receipts rather than a second, parallel history that could disagree
 * with them.
 *
 * ⚠ [routineName] is joined here rather than carried on the run row: a run records *which* routine
 * fired, and the name is a mutable field on the routine. Denormalising it would freeze the name as
 * it was at firing time — which is arguably defensible for an audit trail, but is not what a feed
 * showing "your Good morning routine" should say after you rename it.
 *
 * ⚠ [routineName] is blank when the routine has since been deleted; the feed says so rather than
 * dropping the row, because a firing that happened is a fact about the home.
 */
data class SyncedRoutineRunInfo(
    val firingId: String,
    val routineId: String,
    val routineName: String,
    /** `RoutineRun.STATE_RAN` | `STATE_FAILED` | `STATE_SKIPPED` | `STATE_MISSED` | `STATE_THROTTLED` | `STATE_CLAIMED`. */
    val state: String,
    val claimedAtEpochMs: Long,
)

data class SyncedRoutineInfo(
    val routineId: String,
    val name: String,
    val enabled: Boolean,
    /** `Routine.STATE_ACTIVE` | `Routine.STATE_SUSPENDED`. */
    val state: String,
    /** Why the engine suspended it; `""` when active. */
    val suspendedReason: String,
    val programJson: String,
    val programVersion: Long,
    /**
     * Derived from the newest `RoutineRun` row, never a field on the routine
     * (RE-D10: a mutable `lastRunAt` would race a rename into an LWW clobber and
     * turn a cold config row into a hot one).
     */
    val lastRunAtEpochMs: Long? = null,
)

/**
 * Home's read seam over `:routine-service-client`.
 *
 * Contract, identical to [DeviceSource]'s: an unreachable proxy, a failed read,
 * or a realm with no routines all surface as an **empty list**, never an error —
 * which is exactly the repository's fall-back-to-mock signal.
 */
interface RoutineSource {
    fun routines(): Flow<List<SyncedRoutineInfo>>

    /**
     * Every firing the realm has retained, newest-first — the Activity feed's automation half
     * (`routines-engine.md` RE-1: "run history … feed[s] the Activity tab").
     *
     * ⚠ Bounded by the engine, not here: `RoutineRun.RETAIN_ROWS` caps what exists at all, so the
     * feed reads whatever survived rather than imposing a second, different window.
     *
     * ⚠ Defaults to empty so the mock and any non-IPC implementation keep compiling.
     */
    fun runs(): Flow<List<SyncedRoutineRunInfo>> = flowOf(emptyList())
}

/**
 * Home's write seam. Separate from [RoutineSource] for the same reason
 * `DeviceControlSource` is separate from `DeviceSource`: reads are ambient and
 * always safe, writes are user-initiated and can fail in ways the UI must show.
 */
interface RoutineControlSource {

    /** Creates or replaces a routine. Throws on refusal so the editor can surface why. */
    suspend fun save(
        routineId: String,
        name: String,
        timeZoneId: String,
        programJson: String,
        programVersion: Long,
        homeLatE5: Long,
        homeLonE5: Long,
    )

    suspend fun delete(routineId: String)

    suspend fun setEnabled(routineId: String, enabled: Boolean)

    /**
     * The manual "Run now" gesture — RE-1's end-to-end proof, and something
     * users want anyway. Throws with the engine's reason when it stands down.
     */
    suspend fun runNow(routineId: String)
}
