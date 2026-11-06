package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension function for DSL usage.
 *
 * After `sync-backend-relay-plan.md` §5.1 (HLC piggyback) landed, this op carries
 * the device's signed HLC tip and returns a [ChangesResponse] wrapping both the
 * pulled blocks and every other realm device's last-known signed tip. Receivers
 * compare those peer tips against their own observation history to detect
 * divergence (forked view, peer regression) per `threat-model.md` T-T-04.
 */
suspend fun SyncService.getChanges(
    sinceTimestamp: Long,
    tip: SignedHlcTip
): TResult<ChangesResponse> {
    val operation = findOperation<GetChanges>(GetChanges.ID)
    return operation.getChanges(sinceTimestamp, tip).asTaskInteractor<ChangesResponse>().execute()
}

/**
 * Operation for fetching new sync blocks (and peer HLC tips) from the backend.
 */
interface GetChanges : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getChanges(sinceTimestamp: Long, tip: SignedHlcTip): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getChanges"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                int(Params.SINCE_TIMESTAMP)
                composite(Params.TIP, type = SignedHlcTip.Schema)
            },
            returnType = ChangesResponse.Schema.toType()
        )
    }

    object Params {
        const val SINCE_TIMESTAMP = "$ID/sinceTimestamp"
        const val TIP = "$ID/tip"
    }
}
