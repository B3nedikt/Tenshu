package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the ApprovePairingRequest operation.
 */
suspend fun SyncService.approvePairingRequest(deviceId: String): TResult<Data?> =
    findOperation<ApprovePairingRequest>(ApprovePairingRequest.ID)
        .approvePairingRequest(deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface ApprovePairingRequest : Operation {

    override val schema get() = Schema

    fun approvePairingRequest(deviceId: String): RequestObject

    companion object {
        const val ID = "tenshu/sync/approvePairingRequest"
        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.DEVICE_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DEVICE_ID = "$ID/deviceId"
    }
}
