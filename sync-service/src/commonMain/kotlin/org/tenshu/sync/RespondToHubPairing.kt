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
 * Phone-side responder entry for **headless hub pairing** (hub proposal §11, H2): parse the
 * scanned printed `tenshu://hub?…` label, fetch the hub's signed ephemeral offer from the
 * relay's `/sync/pairing/hub-offer` mailbox, **verify the Ed25519 signature against the
 * printed verify key** (the anti-MITM anchor — the relay cannot substitute an ephemeral key
 * that verifies against what the user physically scanned), then stash the standard responder
 * session under the session-scoped response hash `SHA-256(secret || ephemeralPub)` and return
 * the approval prompt. From there [ApprovePairingOffer]/[DenyPairingOffer] run **unchanged**.
 *
 * There is deliberately **no SAS** in this variant: the printed-key signature verification
 * replaces the simultaneous-comparison ceremony a headless device cannot render (§11.4) — the
 * returned prompt's `sas` field is empty and the approval copy must say why.
 */
suspend fun SyncService.respondToHubPairing(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
    findOperation<RespondToHubPairing>(RespondToHubPairing.ID)
        .respondToHubPairing(scannedUri)
        .asTaskInteractor<PairingOfferApprovalPrompt>()
        .execute()

interface RespondToHubPairing : Operation {

    fun respondToHubPairing(scannedUri: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/respondToHubPairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { string(Params.SCANNED_URI) },
            returnType = PairingOfferApprovalPrompt.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )

        /*
         * ⚠ The `ERROR_*` message prefixes that used to live here are gone. They were real codes
         * travelling inside `GenericError.message`, so the only way to match one was to search
         * English (`core-error-copy-catalogue.md` §5.6). The op now throws `PairingFailedError`
         * with `Reasons.OFFER_NOT_FOUND` / `Reasons.VERIFY_FAILED`, or the existing
         * `RelayRouteUnsupportedError` for a relay that predates the route.
         */
    }

    object Params {
        const val SCANNED_URI = "$ID/scannedUri"
    }
}
