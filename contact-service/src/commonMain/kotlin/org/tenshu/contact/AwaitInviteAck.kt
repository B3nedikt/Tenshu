package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TaskHandle
import org.tenshu.core.asCancellableTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Invitee-side ack wait (`contact-invitation-handshake.md` §4 step 5) — the
 * `AwaitPairing` Layer-2 shape: a cancellable task slow-polling
 * `GET /sync/invite/ack` until the sealed ack arrives or the per-session
 * budget elapses. On an `accepted` ack (after the `K_link` + pinned-inviter-
 * key checks) it creates the mirror [TenshuIdentity] + [Person] for the
 * inviter and frees the relay mailbox (`POST /sync/invite/done`).
 *
 * Returns an [InviteAckResult] whose `status` is one of [Result]; on
 * `ACCEPTED` it also carries the ack's contents (peer name claim,
 * fingerprint, advisory share intent) for the redeem screen's success card
 * (`drive-manage-access-ux.md` §3.2). `PENDING` (budget elapsed) is
 * resumable: the `PendingRedemption` survives in secure storage (≤ 72 h), so
 * the next app session re-invokes with the same `inviteId`.
 */
suspend fun ContactService.awaitInviteAck(
    inviteId: String,
    sasConfirmed: Boolean = false,
): TaskHandle<InviteAckResult> =
    findOperation<AwaitInviteAck>(AwaitInviteAck.ID)
        .awaitInviteAck(inviteId, sasConfirmed)
        .asCancellableTaskInteractor<InviteAckResult>()
        .execute()

interface AwaitInviteAck : Operation {

    override val schema get() = Schema

    fun awaitInviteAck(inviteId: String, sasConfirmed: Boolean): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/awaitInviteAck"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.INVITE_ID)
                boolean(Params.SAS_CONFIRMED, isOptional = true)
            },
            returnType = InviteAckResult.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema),
        )
    }

    object Params {
        const val INVITE_ID = "$ID/inviteId"

        /**
         * True in the QR-in-person flow after the user confirmed the §5.3 SAS
         * — the inviter identity is then born `VERIFIED`/`QR_SAS` instead of
         * `UNVERIFIED`/`INVITE_LINK`.
         */
        const val SAS_CONFIRMED = "$ID/sasConfirmed"
    }

    /** The [InviteAckResult.status] values. */
    object Result {
        /** Mutual close complete — inviter records created locally. */
        const val ACCEPTED = "ACCEPTED"

        /** The inviter declined; no records created. */
        const val REJECTED = "REJECTED"

        /** Budget elapsed without an ack — resume next session. */
        const val PENDING = "PENDING"

        /** Invite expired/revoked, or no redemption state on this device. */
        const val GONE = "GONE"

        /** Ack failed the `K_link` / pinned-inviter-key checks (forgery). */
        const val INVALID = "INVALID"
    }
}
