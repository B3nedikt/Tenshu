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
 * Runs one routine immediately on **this** device — the manual "Run now"
 * gesture, and RE-1's cheapest possible end-to-end proof of the whole engine.
 *
 * ⭐ Deliberately **bypasses the RE-D2 executor claim**: the user is here, on
 * this device, asking for it now. Deferring their tap to whichever device holds
 * the claim would be both slower and inexplicable. It still goes through the
 * full firing sequence otherwise — conditions are evaluated, the safety gate
 * applies, the journal is written before any action, and a [RoutineRun] row
 * records the outcome.
 *
 * A manual firing mints a fresh chain root, so a routine that chains into
 * others produces one traceable tree with `depth` starting at 0.
 *
 * ⚠ It ignores [Routine.enabled] — running a disabled routine on purpose is a
 * legitimate way to test one while authoring it — but it honours
 * [Routine.STATE_SUSPENDED], because a suspended routine is one the engine has
 * already decided is misbehaving.
 */
suspend fun RoutineService.runRoutineNow(routineId: String): TResult<Data?> =
    findOperation<RunRoutineNow>(RunRoutineNow.ID)
        .runRoutineNow(routineId)
        .asTaskInteractor<Data?>()
        .execute()

interface RunRoutineNow : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun runRoutineNow(routineId: String): RequestObject

    companion object {
        const val ID = "tenshu/routine/service/runRoutineNow"

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
