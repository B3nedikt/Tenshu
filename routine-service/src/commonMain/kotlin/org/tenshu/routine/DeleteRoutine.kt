package org.tenshu.routine

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Deletes one [Routine] row (an ordinary tombstone, ADR 0006). Every engine
 * re-arms when the deletion arrives.
 *
 * ⚠ Deliberately does **not** delete the routine's [RoutineRun] history: the
 * Activity tab should still be able to say what a since-deleted routine did,
 * and the run rows age out on their own retention (`RoutineRun.RETAIN_ROWS` /
 * `RETAIN_MS`).
 */
suspend fun RoutineService.deleteRoutine(routineId: String): TResult<Data?> =
    findOperation<DeleteRoutine>(DeleteRoutine.ID)
        .deleteRoutine(routineId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteRoutine : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun deleteRoutine(routineId: String): RequestObject

    companion object {
        const val ID = "tenshu/routine/service/deleteRoutine"

        val Schema = operation(
            id = ID,
            receiver = RoutineService.Schema.toType(),
            params = {
                string(id = Params.ROUTINE_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ROUTINE_ID = "$ID/routineId"
    }
}
