package org.tenshu.sync.relay.mailbox

/**
 * Relay-side state for the **contact-invitation handshake mailbox**
 * (`docs/proposals/contact-invitation-handshake.md` §6): per invite, routing
 * metadata (`inviteId → ownerId, expiry, state`) plus the two **sealed**
 * payload blobs (response, ack).
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md` pattern). Two
 * backends:
 *   - [InviteStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteInviteStore` — durable.
 *
 * Zero-trust posture (House Rule #1): every stored field is routing metadata
 * or sealed ciphertext. The relay never holds `K_link` **or its hash** — if a
 * secret-derived field ever shows up here, the design has been violated
 * (proposal §12 gotcha). The state machine is single-slot by design
 * (`OPEN → RESPONDED → ACKED`, §4 step 3); `REVOKED` is a tombstone kept
 * until expiry so a redeeming invitee sees 410, not 404.
 *
 * **Retention (§6):** record until `expiresAt`; once a response is posted the
 * record (and its blobs) survives until `max(expiresAt, respondedAt + 72 h)`
 * so an offline inviter/invitee can still complete; `done` deletes
 * immediately, [gcSweep] is the fallback.
 */
interface InviteRegistry : OwnerScopedStore {

    /** Outcome of `POST /sync/invite`. */
    enum class RegisterResult {
        /** Registered; the route responds 201. */
        REGISTERED,

        /** `inviteId` already exists (any owner, any state) — 409. */
        DUPLICATE,

        /** The owner is at the open-invite cap — 409. */
        CAP_EXCEEDED,
    }

    /** Outcome of `POST /sync/invite/response`. */
    enum class ResponseResult {
        /** Stored; state `OPEN → RESPONDED`. */
        POSTED,

        /** Unknown `inviteId` — 404. */
        UNKNOWN,

        /** Expired or revoked — 410. */
        GONE,

        /** The single response slot is taken — 409. */
        ALREADY_RESPONDED,
    }

    /** Outcome of `POST /sync/invite/ack`. */
    enum class AckResult {
        /** Stored; state `RESPONDED → ACKED`. */
        POSTED,

        /** Absent or not owned by the caller — 404 (existence-leak-proof). */
        UNKNOWN,

        /** No response to ack yet, or already acked — 409. */
        CONFLICT,
    }

    /** Outcome of `GET /sync/invite/ack`. */
    sealed interface AckFetch {
        /** The sealed ack — 200. */
        data class Found(val sealedAck: ByteArray) : AckFetch

        /** Unknown invite, or no ack posted yet (keep polling) — 404. */
        data object NotFound : AckFetch

        /** Expired or revoked — 410 (the invitee stops polling). */
        data object Gone : AckFetch
    }

    /** One pending sealed response for `GET /sync/invite/responses`. */
    data class PendingResponse(val inviteId: String, val sealedResponse: ByteArray)

    /**
     * Register a fresh invite mailbox. [expiresAtMs] is the already-clamped
     * expiry (the route applies `min(requested, now + 7 d)`).
     * [maxOpenPerOwner] caps the owner's live (`OPEN`, unexpired) invites —
     * checked atomically with the insert.
     */
    fun register(
        inviteId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxOpenPerOwner: Int,
    ): RegisterResult

    /**
     * Owner-side revoke. Clears any blobs and leaves a `REVOKED` tombstone
     * until expiry (redeeming a revoked link ⇒ 410). False if absent or not
     * owned by [ownerId] (the route maps both to 404).
     */
    fun revoke(inviteId: String, ownerId: String): Boolean

    /** Invitee posts the sealed response — the single slot (§4 step 3). */
    fun postResponse(
        inviteId: String,
        sealedResponse: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): ResponseResult

    /** All not-yet-acked sealed responses across [ownerId]'s invites. */
    fun pendingResponses(ownerId: String): List<PendingResponse>

    /** Inviter posts the sealed ack; requires state `RESPONDED` + ownership. */
    fun postAck(
        inviteId: String,
        ownerId: String,
        sealedAck: ByteArray,
    ): AckResult

    /** Invitee polls for the sealed ack (`inviteId` is the capability). */
    fun getAck(inviteId: String, nowMs: Long = System.currentTimeMillis()): AckFetch

    /** Invitee signals mutual close — frees all state for [inviteId] now. */
    fun done(inviteId: String): Boolean

    /** Drop records past the §6 retention rule. Returns the count dropped. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int

    /** Number of records currently held (test/diagnostics hook). */
    fun recordCount(): Int
}
