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

suspend fun SyncService.cancelPairing(pairingSecret: String): TResult<TBoolean> =
    findOperation<CancelPairing>(CancelPairing.ID)
        .cancelPairing(pairingSecret)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Discards the proxy-side ephemeral keypair stashed under [pairingSecret]
 * by `InitiatePairing` — i.e. burns the local handshake context per
 * `docs/sync/pairing-flow.md` §5 ("Closing the QR dialog burns the
 * handshake"). Called from the requester's PairingWizard when the user
 * backs out before approval. The relay still holds the request until
 * `sync-pairing-flow-improvements.md` Step 4 single-use semantics reach the
 * request flow (they already cover the offer flow — `PairingOfferRegistry`); for now
 * this just frees Device B's `SyncManagerImpl.pendingHandshakes` slot
 * so the in-memory keypair doesn't outlive the user's intent.
 *
 * Returns `Success(true)` whether or not a handshake was actually
 * present — the call is idempotent.
 */
interface CancelPairing : Operation {

    fun cancelPairing(pairingSecret: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/cancelPairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PAIRING_SECRET)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PAIRING_SECRET = "$ID/pairingSecret"
    }
}
