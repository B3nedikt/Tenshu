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
 * Offerer teardown (`sync-pairing-inverted-direction.md` §5.1): zero the offerer's ephemeral
 * private key and drop the offer session. Idempotent — `Success(true)` whether or not a session
 * was present.
 */
suspend fun SyncService.cancelPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
    findOperation<CancelPairingOffer>(CancelPairingOffer.ID)
        .cancelPairingOffer(pairingSecretHash)
        .asTaskInteractor<TBoolean>()
        .execute()

interface CancelPairingOffer : Operation {

    fun cancelPairingOffer(pairingSecretHash: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/cancelPairingOffer"

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
