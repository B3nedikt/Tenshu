package org.tenshu.storage

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Tier-3 pre-flight (`docs/proposals/storage-capacity-gating.md` §2): does
 * [requiredBytes] fit in the owner's remaining storage? Returns a
 * [CapacityResult]. Reserved for the deliberate, ahead-of-time case — a
 * folder / multi-file upload sums its sizes locally and asks once before
 * starting a long job, rather than failing file-by-file. Single-item actions
 * don't call this; they rely on the Tier-2 op-embedded check.
 */
suspend fun StorageService.checkCapacity(
    requiredBytes: Long,
): TResult<CapacityResult?> =
    findOperation<CheckCapacity>(CheckCapacity.ID)
        .checkCapacity(requiredBytes)
        .asTaskInteractor<CapacityResult?>()
        .execute()

interface CheckCapacity : Operation {

    override val schema: OperationSchema get() = Schema

    fun checkCapacity(requiredBytes: Long): RequestObject

    companion object {
        const val ID = "tenshu/storage/service/checkCapacity"

        val Schema = operation(
            id = ID,
            receiver = StorageService.Schema.toType(),
            params = {
                int(id = Params.REQUIRED_BYTES)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REQUIRED_BYTES = "$ID/requiredBytes"
    }
}
