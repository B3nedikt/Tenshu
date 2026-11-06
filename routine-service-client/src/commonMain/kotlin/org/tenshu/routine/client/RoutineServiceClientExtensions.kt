package org.tenshu.routine.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.deleteRoutine
import org.tenshu.routine.runRoutineNow
import org.tenshu.routine.saveRoutine
import org.tenshu.routine.setRoutineEnabled

val TenshuClient.routineService: RoutineService
    get() = getService(RoutineService.ID)

/** All known routines — a local read of the synced rows. */
suspend fun RoutineService.getRoutines(): List<Routine> =
    routines.getAll().getOrNull()?.toList().orEmpty()

/** Reactive twin of [getRoutines]: re-emits on local edit or sync arrival. */
fun RoutineService.routinesFlow(): Flow<List<Routine>> =
    routines.flow().map { result ->
        (result as? TResult.Success)?.value?.toList().orEmpty()
    }

/**
 * Run history — also the cross-device dedupe receipts (RE-D3).
 *
 * ⚠ This is where a routine's `lastRunAt` comes from: it is **derived**, never a
 * field on the routine row (RE-D10).
 */
suspend fun RoutineService.getRoutineRuns(): List<RoutineRun> =
    routineRuns.getAll().getOrNull()?.toList().orEmpty()

/** Reactive twin of [getRoutineRuns] — feeds Home's Activity tab. */
fun RoutineService.routineRunsFlow(): Flow<List<RoutineRun>> =
    routineRuns.flow().map { result ->
        (result as? TResult.Success)?.value?.toList().orEmpty()
    }

/** Typed wrapper over `SaveRoutine`; throws on refusal (an invalid program). */
suspend fun RoutineService.save(
    routineId: String,
    name: String,
    timeZoneId: String,
    programJson: String,
    programVersion: Long,
    homeLatE5: Long = Routine.NO_COORDINATE,
    homeLonE5: Long = Routine.NO_COORDINATE,
) {
    saveRoutine(
        routineId = routineId,
        name = name,
        timeZoneId = timeZoneId,
        programJson = programJson,
        programVersion = programVersion,
        homeLatE5 = homeLatE5,
        homeLonE5 = homeLonE5,
    ).orThrow()
}

suspend fun RoutineService.delete(routineId: String) {
    deleteRoutine(routineId).orThrow()
}

suspend fun RoutineService.setEnabled(routineId: String, enabled: Boolean) {
    setRoutineEnabled(routineId, enabled).orThrow()
}

/** The manual gesture. Throws with the engine's reason when it stands down. */
suspend fun RoutineService.runNow(routineId: String) {
    runRoutineNow(routineId).orThrow()
}

/**
 * ⚠⚠ These ops return a `TResult`, so a failure is a **value**, not a throw —
 * a caller that ignores it silently swallows the refusal (the `orThrow` lesson
 * from the place-geocoding track). Every wrapper above funnels through here.
 */
private fun <T : Data?> TResult<T>.orThrow() {
    if (this is TResult.Failure) throw IllegalStateException(error.toString())
}
