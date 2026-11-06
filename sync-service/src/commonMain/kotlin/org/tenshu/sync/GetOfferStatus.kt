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
 * Offerer poll (`sync-pairing-inverted-direction.md` §3.3 step 4): peek the offer rendezvous
 * by `pairingSecretHash`. On an approved response the proxy derives the SAS, decrypts + installs
 * the keys, and self-pushes this device's `Device` row; returns [OfferStatus] `approved` (+ SAS),
 * `denied`, or `pending` while still waiting. The UI loops this (like `GetPairingStatus`).
 */
suspend fun SyncService.getOfferStatus(pairingSecretHash: String): TResult<OfferStatus> =
    findOperation<GetOfferStatus>(GetOfferStatus.ID)
        .getOfferStatus(pairingSecretHash)
        .asTaskInteractor<OfferStatus>()
        .execute()

interface GetOfferStatus : Operation {

    fun getOfferStatus(pairingSecretHash: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getOfferStatus"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { string(Params.PAIRING_SECRET_HASH) },
            returnType = OfferStatus.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PAIRING_SECRET_HASH = "$ID/pairingSecretHash"
    }
}
