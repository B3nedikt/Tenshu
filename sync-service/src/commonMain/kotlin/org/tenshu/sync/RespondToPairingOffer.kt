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
 * Responder op (an existing realm device, `sync-pairing-inverted-direction.md` §3.3 step 2-3):
 * parse the scanned `tenshu://offer?…` URI, generate an ephemeral X25519 keypair, derive the
 * shared secret + 6-digit SAS, stash the responder session keyed by `SHA-256(secret)`, and
 * return the [PairingOfferApprovalPrompt] (device name + SAS) the approval UI shows.
 */
suspend fun SyncService.respondToPairingOffer(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
    findOperation<RespondToPairingOffer>(RespondToPairingOffer.ID)
        .respondToPairingOffer(scannedUri)
        .asTaskInteractor<PairingOfferApprovalPrompt>()
        .execute()

interface RespondToPairingOffer : Operation {

    fun respondToPairingOffer(scannedUri: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/respondToPairingOffer"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { string(Params.SCANNED_URI) },
            returnType = PairingOfferApprovalPrompt.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SCANNED_URI = "$ID/scannedUri"
    }
}
