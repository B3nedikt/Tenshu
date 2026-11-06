package org.tenshu.routine

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Creates or replaces one [Routine] row on the caller's proxy; ordinary sync
 * carries it to every other device, each of whose engines re-arms (RE-D5).
 *
 * The proxy-side implementation validates the program (⚠ an unparseable program
 * is refused *here*, at authoring time, rather than becoming a routine that
 * silently never runs), stamps `createdAtEpochMs` / `updatedAtEpochMs` from the
 * proxy clock, and records `authorDeviceId` — which the T-RE-02 revocation rule
 * later reads.
 *
 * ⚠ The caller supplies `timeZoneId` and the home coordinates because they
 * belong to the **routine**, not the executing device (RE-D3).
 */
suspend fun RoutineService.saveRoutine(
    routineId: String,
    name: String,
    timeZoneId: String,
    programJson: String,
    programVersion: Long,
    homeLatE5: Long = Routine.NO_COORDINATE,
    homeLonE5: Long = Routine.NO_COORDINATE,
): TResult<Data?> =
    findOperation<SaveRoutine>(SaveRoutine.ID)
        .saveRoutine(
            routineId = routineId,
            name = name,
            timeZoneId = timeZoneId,
            programJson = programJson,
            programVersion = programVersion,
            homeLatE5 = homeLatE5,
            homeLonE5 = homeLonE5,
        )
        .asTaskInteractor<Data?>()
        .execute()

interface SaveRoutine : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun saveRoutine(
        routineId: String,
        name: String,
        timeZoneId: String,
        programJson: String,
        programVersion: Long,
        homeLatE5: Long,
        homeLonE5: Long,
    ): RequestObject

    companion object {
        const val ID = "tenshu/routine/service/saveRoutine"

        val Schema = operation(
            id = ID,
            receiver = RoutineService.Schema.toType(),
            params = {
                string(id = Params.ROUTINE_ID)
                string(id = Params.NAME)
                string(id = Params.TIME_ZONE_ID)
                string(id = Params.PROGRAM_JSON)
                int(id = Params.PROGRAM_VERSION)
                int(id = Params.HOME_LAT_E5)
                int(id = Params.HOME_LON_E5)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ROUTINE_ID = "$ID/routineId"
        const val NAME = "$ID/name"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
        const val PROGRAM_JSON = "$ID/programJson"
        const val PROGRAM_VERSION = "$ID/programVersion"
        const val HOME_LAT_E5 = "$ID/homeLatE5"
        const val HOME_LON_E5 = "$ID/homeLonE5"
    }
}
