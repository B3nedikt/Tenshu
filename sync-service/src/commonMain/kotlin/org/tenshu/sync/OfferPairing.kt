package org.tenshu.sync

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
 * Offerer op (the new device, `sync-pairing-inverted-direction.md` §3.3 step 1): generate an
 * ephemeral X25519 keypair + a 128-bit pairing secret, stash the offer session keyed by
 * `SHA-256(secret)`, and return the `tenshu://offer?…` QR/URI to display + the hash to poll.
 */
suspend fun SyncService.offerPairing(deviceName: String): TResult<OfferPairingSession> =
    findOperation<OfferPairing>(OfferPairing.ID)
        .offerPairing(deviceName)
        .asTaskInteractor<OfferPairingSession>()
        .execute()

interface OfferPairing : Operation {

    fun offerPairing(deviceName: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/offerPairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { string(Params.DEVICE_NAME) },
            returnType = OfferPairingSession.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DEVICE_NAME = "$ID/deviceName"
    }
}
