package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the GetPairingStatus operation.
 *
 * Returns `Success(true)` once the relay has delivered the encrypted master
 * key and the SyncManager has been initialized; `Success(false)` while the
 * handshake is still pending.
 */
suspend fun SyncService.getPairingStatus(
    pairingSecret: String,
    otherDevicePublicKey: ByteArray
): TResult<TBoolean> =
    findOperation<GetPairingStatus>(GetPairingStatus.ID)
        .getPairingStatus(pairingSecret, otherDevicePublicKey)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Operation for polling the pairing status on Device B.
 * If successful, the device is now paired and initialized with the master keys.
 */
interface GetPairingStatus : Operation {

    fun getPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getPairingStatus"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PAIRING_SECRET)
                byteArray(Params.OTHER_DEVICE_PUBLIC_KEY)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PAIRING_SECRET = "$ID/pairingSecret"
        const val OTHER_DEVICE_PUBLIC_KEY = "$ID/otherDevicePublicKey"
    }
}
