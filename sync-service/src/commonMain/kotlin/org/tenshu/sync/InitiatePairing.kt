package org.tenshu.sync

import org.tenshu.core.Operation
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
 * Extension for triggering the InitiatePairing operation.
 */
suspend fun SyncService.initiatePairing(
    ownerId: String,
    pairingSecret: String,
    deviceName: String,
    otherDevicePublicKey: ByteArray,
): TResult<PairingInitiation> =
    findOperation<InitiatePairing>(InitiatePairing.ID)
        .initiatePairing(ownerId, pairingSecret, deviceName, otherDevicePublicKey)
        .asTaskInteractor<PairingInitiation>()
        .execute()

/**
 * Operation for initiating the pairing process on Device B (the requester
 * per `docs/sync/pairing-flow.md` §3). Returns a [PairingInitiation]
 * carrying this device's ephemeral public key plus the 6-digit Short
 * Authentication String per
 * `docs/proposals/sync-pairing-flow-improvements.md` §3.2 — the SAS
 * lets the user verify against the value the host displays before
 * approving the request.
 */
interface InitiatePairing : Operation {

    fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/initiatePairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.OWNER_ID)
                string(Params.PAIRING_SECRET)
                string(Params.DEVICE_NAME)
                byteArray(Params.OTHER_DEVICE_PUBLIC_KEY)
            },
            returnType = PairingInitiation.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val OWNER_ID = "$ID/ownerId"
        const val PAIRING_SECRET = "$ID/pairingSecret"
        const val DEVICE_NAME = "$ID/deviceName"
        const val OTHER_DEVICE_PUBLIC_KEY = "$ID/otherDevicePublicKey"
    }
}
