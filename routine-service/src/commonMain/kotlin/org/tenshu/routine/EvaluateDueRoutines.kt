package org.tenshu.routine

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.Punctual
import org.tenshu.core.trait.TerminalOperation

/**
 * The engine's wake-up: evaluates every routine this device is eligible for and
 * whose trigger instant has arrived, then re-arms the next wake
 * (`routines-engine.md` §4.9).
 *
 * ⭐ **Parameter-less by design.** `ScheduleItem` carries no parameters
 * (`core-background-processing.md` Phase 5), and that is exactly right here: the
 * engine keeps **one** `ONE_SHOT` item armed to the *earliest* upcoming firing
 * across everything it is eligible for, and this op finds its own work when it
 * lands. One OS alarm beats N.
 *
 * ⚠⚠ Carries [HeadlessSafe] because `ScheduleImplementation` is **fail-closed**
 * and rejects anything else at registration. Without the trait this surfaces as
 * a confusing runtime refusal rather than a compile-time or wiring error.
 *
 * ⚠⚠ Its `ConstraintSpec` must be **empty** (RE-D5). Idle + charging + unmetered
 * is the *compaction* scheduling class and is exactly wrong here: a routine that
 * only fires while the phone is on the charger is not a routine. Nothing about a
 * routine is deferrable maintenance.
 */
suspend fun RoutineService.evaluateDueRoutines(): TResult<Data?> =
    findOperation<EvaluateDueRoutines>(EvaluateDueRoutines.ID)
        .evaluateDueRoutines()
        .asTaskInteractor<Data?>()
        .execute()

interface EvaluateDueRoutines : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun evaluateDueRoutines(): RequestObject

    companion object {
        const val ID = "tenshu/routine/service/evaluateDueRoutines"

        val Schema = operation(
            id = ID,
            receiver = RoutineService.Schema.toType(),
            traits = listOf(
                IoOperation.Schema,
                TerminalOperation.Schema,
                HeadlessSafe.Schema,
                // ⭐ RE-D5: "at sunset" has no useful deferred form. A scheduler
                // that batches this into its next convenient wake-up has not
                // been slightly late — it has failed.
                Punctual.Schema,
            ),
        )
    }
}
