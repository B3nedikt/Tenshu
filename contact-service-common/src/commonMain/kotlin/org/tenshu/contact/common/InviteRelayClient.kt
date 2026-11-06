package org.tenshu.contact.common

/**
 * Client interface for the relay's `/sync/invite/…` mailbox
 * (`contact-invitation-handshake.md` §6; served by `InviteRouting` in
 * `:sync-relay-server`). Base64-JSON envelopes per relay conventions; the
 * production impl is `RestInviteRelayClient` (`:contact-service-proxy`),
 * authenticating the four inviter-side calls with the device-key JWT seam.
 * The invitee-side calls ([postResponse], [fetchAck], [done]) are anonymous
 * by design — `inviteId` is the capability and the invitee stays
 * protocol-anonymous (T-CI-06).
 *
 * **Deliberately not part of `SyncRelay`** (§12: do not widen it) — the
 * invite mailbox is the contact service's own wire surface.
 *
 * Every sealed blob is opaque to the relay; this client never sends
 * `K_link` or any derivative of it.
 */
interface InviteRelayClient {

    /**
     * Register the mailbox (`POST /sync/invite`, JWT). Returns the relay's
     * (possibly 7-day-clamped) expiry. Throws [InviteRelayException] /
     * [InviteConflictException] (id taken or open-invite cap).
     */
    suspend fun registerInvite(inviteId: String, expiresAtMillis: Long): Long

    /**
     * Revoke before redemption (`DELETE /sync/invite/{id}`, JWT, owner-scoped).
     * Unknown/foreign ids are a no-op (the relay 404s; revocation is
     * idempotent from the caller's view).
     */
    suspend fun revokeInvite(inviteId: String)

    /**
     * Invitee posts the sealed response (`POST /sync/invite/response`,
     * anonymous; single slot). Throws [InviteUnknownException] (404),
     * [InviteGoneException] (410), [InviteConflictException] (409 — slot
     * taken, the T-CI-01 burn signal).
     */
    suspend fun postResponse(inviteId: String, sealedResponse: ByteArray)

    /** All pending sealed responses for this owner (`GET /sync/invite/responses`, JWT). */
    suspend fun pendingResponses(): List<PendingResponse>

    /**
     * Inviter posts the sealed ack (`POST /sync/invite/ack`, JWT).
     * Throws [InviteUnknownException] / [InviteConflictException].
     */
    suspend fun postAck(inviteId: String, sealedAck: ByteArray)

    /** Invitee polls for the ack (`GET /sync/invite/ack`, anonymous). */
    suspend fun fetchAck(inviteId: String): AckPoll

    /** Free all relay state (`POST /sync/invite/done`, anonymous, idempotent). */
    suspend fun done(inviteId: String)

    data class PendingResponse(val inviteId: String, val sealedResponse: ByteArray)

    sealed interface AckPoll {
        /** 200 — the sealed ack. */
        data class Found(val sealedAck: ByteArray) : AckPoll

        /** 404 — nothing yet; keep polling. */
        data object NotYet : AckPoll

        /** 410 — expired or revoked; stop polling. */
        data object Gone : AckPoll
    }
}

/** Relay answered 404 — unknown invite. */
class InviteUnknownException(message: String) : RuntimeException(message)

/** Relay answered 410 — invite expired or revoked. */
class InviteGoneException(message: String) : RuntimeException(message)

/** Relay answered 409 — duplicate id, cap reached, or response slot taken. */
class InviteConflictException(message: String) : RuntimeException(message)

/** Transport failure or unexpected relay status. */
class InviteRelayException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
