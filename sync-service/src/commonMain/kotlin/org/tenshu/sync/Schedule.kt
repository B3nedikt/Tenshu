package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * Phase 5 (§3.2(b)): register a head-less invocation. Rejected with a
 * failure if [opId]'s schema is not `isHeadlessSafe()` (§6.4 — checked here
 * *and* re-checked by the drain loop before dispatch). `dedupKey` coalesces
 * repeat enqueueings to one pending [ScheduleItem] (§3.3).
 */
suspend fun ScheduleService.schedule(
    opId: String,
    scheduleSpec: ScheduleSpec,
    constraintSpec: ConstraintSpec,
    retryPolicy: RetryPolicy,
    dedupKey: String
): TResult<Data?> =
    findOperation<Schedule>(Schedule.ID)
        .schedule(opId, scheduleSpec, constraintSpec, retryPolicy, dedupKey)
        .asTaskInteractor<Data?>()
        .execute()

interface Schedule : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun schedule(
        opId: String,
        scheduleSpec: ScheduleSpec,
        constraintSpec: ConstraintSpec,
        retryPolicy: RetryPolicy,
        dedupKey: String
    ): RequestObject

    companion object {
        const val ID = "${ScheduleService.ID}/schedule"

        val Schema = operation(
            id = ID,
            receiver = ScheduleService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema),
            params = {
                string(Params.OP_ID)
                composite(Params.SCHEDULE_SPEC, type = ScheduleSpec.Schema)
                composite(Params.CONSTRAINT_SPEC, type = ConstraintSpec.Schema)
                composite(Params.RETRY_POLICY, type = RetryPolicy.Schema)
                string(Params.DEDUP_KEY)
            },
            returnType = ScheduleItem.Schema.toType()
        )
    }

    object Params {
        const val OP_ID = "$ID/opId"
        const val SCHEDULE_SPEC = "$ID/scheduleSpec"
        const val CONSTRAINT_SPEC = "$ID/constraintSpec"
        const val RETRY_POLICY = "$ID/retryPolicy"
        const val DEDUP_KEY = "$ID/dedupKey"
    }
}
