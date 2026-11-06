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
 * Responder deny (`sync-pairing-inverted-direction.md` §3.3): POST a signed `status:"denied"`
 * offer-response (no payload, no registration) so the offerer's poll terminates, and drop the
 * responder session. Idempotent.
 */
suspend fun SyncService.denyPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
    findOperation<DenyPairingOffer>(DenyPairingOffer.ID)
        .denyPairingOffer(pairingSecretHash)
        .asTaskInteractor<TBoolean>()
        .execute()

interface DenyPairingOffer : Operation {

    fun denyPairingOffer(pairingSecretHash: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/denyPairingOffer"

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
