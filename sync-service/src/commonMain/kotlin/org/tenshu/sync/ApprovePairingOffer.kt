package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
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
 * Responder approve (`sync-pairing-inverted-direction.md` §3.3 step 3): build + AEAD-seal the
 * pairing payload, sign the offer-response + a master/A33 delegation admitting the offerer's
 * device, POST the approved offer-response, and author the offerer's `Device` row. `Success(true)`
 * once the relay has stored + registered.
 */
suspend fun SyncService.approvePairingOffer(pairingSecretHash: String): TResult<TBoolean> =
    findOperation<ApprovePairingOffer>(ApprovePairingOffer.ID)
        .approvePairingOffer(pairingSecretHash)
        .asTaskInteractor<TBoolean>()
        .execute()

interface ApprovePairingOffer : Operation {

    fun approvePairingOffer(pairingSecretHash: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/approvePairingOffer"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { string(Params.PAIRING_SECRET_HASH) },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PAIRING_SECRET_HASH = "$ID/pairingSecretHash"
    }
}
