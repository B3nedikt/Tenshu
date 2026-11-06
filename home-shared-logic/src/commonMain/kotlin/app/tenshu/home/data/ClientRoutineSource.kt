package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.TenshuClient
import org.tenshu.routine.Routine
import org.tenshu.routine.client.delete
import org.tenshu.routine.client.routineRunsFlow
import org.tenshu.routine.client.routineService
import org.tenshu.routine.client.routinesFlow
import org.tenshu.routine.client.runNow
import org.tenshu.routine.client.save
import org.tenshu.routine.client.setEnabled

/**
 * The real [RoutineSource]: Home's Tenshu client reading the synced `Routine`
 * rows and joining each with its newest `RoutineRun` to derive `lastRunAt`.
 *
 * ⭐ The join is the point. RE-D10 keeps `lastRunAt` **off** the routine row (a
 * mutable one would race a rename into an LWW clobber and make a cold config row
 * hot), so "when did this last run" is a *derivation* over the receipts — which
 * are the same rows the Activity tab renders.
 *
 * Every failure mode — no proxy connection, an older Control without the routine
 * wiring, a realm with no routines — degrades to an empty emission, which is the
 * repository's fall-back-to-mock signal (the [ClientDeviceSource] contract).
 */
class ClientRoutineSource(
    private val client: TenshuClient,
) : RoutineSource {

    override fun routines(): Flow<List<SyncedRoutineInfo>> = flow {
        emitAll(
            combine(
                client.routineService.routinesFlow(),
                client.routineService.routineRunsFlow(),
            ) { routines, runs ->
                val newestRunByRoutine = runs
                    .groupBy { it.routineId.get() }
                    .mapValues { (_, rows) -> rows.maxOf { it.claimedAtEpochMs.get() } }
                routines.map { it.toInfo(newestRunByRoutine[it.routineId.get()]) }
            },
        )
    }.catch { emptyOnUnreachable("routines", it) }

    /**
     * ⚠ The same two flows the listing above joins, for the same reason: the run row records a
     * `routineId`, and the name it should be shown under is whatever the routine is called **now**.
     */
    override fun runs(): Flow<List<SyncedRoutineRunInfo>> = flow {
        emitAll(
            combine(
                client.routineService.routinesFlow(),
                client.routineService.routineRunsFlow(),
            ) { routines, runs ->
                val nameById = routines.associate { it.routineId.get() to it.name.get() }
                runs
                    .map { run ->
                        SyncedRoutineRunInfo(
                            firingId = run.firingId.get(),
                            routineId = run.routineId.get(),
                            // ⚠ Blank for a deleted routine — the feed says so rather than dropping
                            // the row, because a firing that happened is a fact about the home.
                            routineName = nameById[run.routineId.get()].orEmpty(),
                            state = run.state.get(),
                            claimedAtEpochMs = run.claimedAtEpochMs.get(),
                        )
                    }
                    .sortedByDescending { it.claimedAtEpochMs }
            },
        )
    }.catch { emptyOnUnreachable("routine-runs", it) }

    private fun Routine.toInfo(lastRunAtEpochMs: Long?) = SyncedRoutineInfo(
        routineId = routineId.get(),
        name = name.get(),
        enabled = enabled.get(),
        state = state.get(),
        suspendedReason = suspendedReason.get(),
        programJson = programJson.get(),
        programVersion = programVersion.get(),
        lastRunAtEpochMs = lastRunAtEpochMs,
    )
}

/**
 * The real [RoutineControlSource].
 *
 * ⚠ Unlike the read side, failures **propagate**: the user pressed a button, so
 * "nothing happened" needs a reason they can see. The typed wrappers in
 * `:routine-service-client` already turn a `TResult.Failure` into a throw — the
 * op-returns-a-value-not-a-throw trap.
 */
class ClientRoutineControlSource(
    private val client: TenshuClient,
) : RoutineControlSource {

    override suspend fun save(
        routineId: String,
        name: String,
        timeZoneId: String,
        programJson: String,
        programVersion: Long,
        homeLatE5: Long,
        homeLonE5: Long,
    ) {
        client.routineService.save(
            routineId = routineId,
            name = name,
            timeZoneId = timeZoneId,
            programJson = programJson,
            programVersion = programVersion,
            homeLatE5 = homeLatE5,
            homeLonE5 = homeLonE5,
        )
    }

    override suspend fun delete(routineId: String) {
        client.routineService.delete(routineId)
    }

    override suspend fun setEnabled(routineId: String, enabled: Boolean) {
        client.routineService.setEnabled(routineId, enabled)
    }

    override suspend fun runNow(routineId: String) {
        client.routineService.runNow(routineId)
    }
}
