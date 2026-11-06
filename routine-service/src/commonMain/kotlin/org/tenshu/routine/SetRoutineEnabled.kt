package org.tenshu.routine

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Flips [Routine.enabled] — the **user's** switch, and only ever the user's.
 *
 * ⚠⚠ The engine must never express suspension through this op (RE-D9): writing
 * `enabled = false` for a runaway would be indistinguishable from the user's own
 * toggle, would silently destroy their intent, and would leave them re-enabling
 * a routine with no idea why it stopped. Engine suspension writes
 * [Routine.state] = [Routine.STATE_SUSPENDED] with a reason, and clearing it is
 * one tap plus an explanation.
 *
 * Clearing a suspension is a *user* action, so this op also resets
 * [Routine.state] to [Routine.STATE_ACTIVE] when [enabled] is set true — the
 * "re-enable" gesture in the UI is one control, not two.
 */
suspend fun RoutineService.setRoutineEnabled(
    routineId: String,
    enabled: Boolean,
): TResult<Data?> =
    findOperation<SetRoutineEnabled>(SetRoutineEnabled.ID)
        .setRoutineEnabled(routineId, enabled)
        .asTaskInteractor<Data?>()
        .execute()

interface SetRoutineEnabled : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun setRoutineEnabled(routineId: String, enabled: Boolean): RequestObject

    companion object {
        const val ID = "tenshu/routine/service/setRoutineEnabled"

        val Schema = operation(
            id = ID,
            receiver = RoutineService.Schema.toType(),
            params = {
                string(id = Params.ROUTINE_ID)
                boolean(id = Params.ENABLED)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ROUTINE_ID = "$ID/routineId"
        const val ENABLED = "$ID/enabled"
    }
}
