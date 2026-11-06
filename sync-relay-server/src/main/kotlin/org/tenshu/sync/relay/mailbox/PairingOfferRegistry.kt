package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.relay.PairingResponseInbox

/**
 * Relay-side state for the **offer-flow pairing rendezvous**
 * (`docs/proposals/sync-pairing-inverted-direction.md` §3.4): the one genuinely
 * new relay component the inverted pairing direction needs. The offerer (a
 * brand-new device) has no `ownerId`, so the join flow's owner-keyed pairing
 * mailbox can't address it — instead the responder posts a single sealed
 * offer-response keyed by `pairingSecretHash = SHA-256(pairingSecret)` (hex),
 * and the offerer polls the same hash.
 *
 * One direction only: the B→A hop rides the QR optically; the relay carries
 * just the A→B offer-response. At most one blob per hash, ever — the hash is
 * **single-use** (T6/T-P3): a second post for the same hash is rejected even
 * after the serving TTL lapses, until the retention sweep finally drops the
 * consumed marker (which is far outside the 5-minute pairing window).
 *
 * Zero-trust posture (House Rule #1): the stored record is routing metadata
 * (`ownerId`, `status`, the responder's ephemeral `PublicKeyA`) plus AEAD
 * ciphertext sealed under X25519(privB, PublicKeyA) — the relay never sees the
 * pairing secret (only its hash), `privB`, or any plaintext key material.
 * Posting requires a signature by a registered realm device (T-P2); fetching
 * is anonymous by design — the offerer has no credentials yet (T-P1).
 *
 * Storage SPI (`sync-relay-persistence.md` pattern). Two backends:
 *   - [PairingOfferStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqlitePairingOfferStore` — durable.
 */
interface PairingOfferRegistry : OwnerScopedStore {

    /** Outcome of `POST /sync/pairing/offer-response`. */
    enum class PostResult {
        /** Stored; the route responds 200. */
        STORED,

        /** The hash was already consumed (single-use, T-P3) — 409. */
        CONSUMED,
    }

    /**
     * Outcome of the **scanned announce** — `status:"scanned"`, A1 of
     * `sync-pairing-scanned-announce.md`.
     */
    enum class AnnounceResult {
        /** Stored; the route responds 200. */
        STORED,

        /**
         * An announce already exists for this hash — 409.
         *
         * ⚠⚠ **One announce per hash is the point, not tidiness.** The offerer pins the announced
         * `publicKeyA` and later refuses a terminal response that does not match it (§2.3). If a
         * second announce could overwrite the first, an attacker who saw the QR could re-point that
         * pin at their own key and the lock would verify against the wrong thing — turning the
         * defence into its opposite.
         */
        ALREADY_ANNOUNCED,

        /**
         * A terminal response was already posted for this hash — 409. Announcing after the
         * decision is meaningless, and admitting it would let a late announce displace the blob the
         * offerer is polling for.
         */
        CONSUMED,
    }

    /** Outcome of `GET /sync/pairing/offer-response?hash=…`. */
    sealed interface Fetch {
        /** The stored offer-response — 200. */
        data class Found(val response: OfferResponse) : Fetch

        /** Nothing (yet) at this hash, or past the serving TTL — 404, keep polling. */
        data object NotFound : Fetch
    }

    /**
     * The responder's answer. [payload] is `nonce || AEAD ciphertext` per
     * improvements §11 and present iff [status] == `"approved"`; a signed
     * denial carries no payload (improvements §10 posture).
     *
     * [registration] is the offerer's verified relay registration, present only on an approval.
     * ⚠⚠ It is **applied when the offerer fetches** the response, never when the responder posts
     * it (the join flow's `PairingResponseInbox` design): an approval that expires unfetched must
     * leave no registered device behind, or that device sits in `DeviceRegistry.deviceIds` for
     * ever, never acks a tombstone, and stalls the realm's tombstone-GC quorum. It always names
     * [ownerId], so [OwnerScopedStore.eraseOwner] dropping the row drops the registration too.
     */
    class OfferResponse(
        val ownerId: String,
        val status: String,
        val publicKeyA: ByteArray,
        val payload: ByteArray?,
        val registration: PairingResponseInbox.PendingRegistration? = null,
    ) {
        init {
            require(registration == null || registration.ownerId == ownerId) {
                "an offer-response's registration must name its own realm"
            }
        }
    }

    /**
     * Store the single offer-response for [pairingSecretHash] and mark the
     * hash consumed. [expiresAtMs] bounds *serving* (the offerer's 5-minute
     * window + grace); the consumed marker outlives it until [gcSweep]'s
     * retention cutoff.
     */
    fun post(
        pairingSecretHash: String,
        response: OfferResponse,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): PostResult

    /**
     * Store the responder's **scanned announce** for [pairingSecretHash] (A1).
     *
     * ⚠⚠ **Does NOT consume the hash.** The announce is not the decision — the terminal
     * approve/deny still arrives later through [post] and consumes it exactly as before. This is
     * the one thing an implementation must not get wrong: consuming here would make every offer
     * die at the moment it was scanned.
     *
     * The stored blob carries `status = "scanned"` and no payload; [fetch] serves it **until a
     * terminal response replaces it**, so the offerer's existing poll sees the announce first and
     * the decision second.
     */
    fun postAnnounce(
        pairingSecretHash: String,
        response: OfferResponse,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): AnnounceResult

    /**
     * Offerer poll. [Fetch.Found] only while the serving TTL is live — and only then does the
     * route apply [OfferResponse.registration], so expiry alone undoes an unfetched admission.
     */
    fun fetch(pairingSecretHash: String, nowMs: Long = System.currentTimeMillis()): Fetch

    /**
     * Newest `admittedAt` among **served** (unexpired) terminal responses queuing a registration of
     * [deviceId] into [ownerId] — `DeviceRevocation` orders a `Device` tombstone against it, since
     * the admission is not on record until the offerer fetches.
     */
    fun pendingAdmittedAt(
        ownerId: String,
        deviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): org.tenshu.core.common.util.HlcStamp?

    /**
     * Drop consumed markers (and any blob still attached) whose serving expiry
     * is more than [retentionMs] in the past. Returns the count dropped.
     */
    fun gcSweep(
        nowMs: Long = System.currentTimeMillis(),
        retentionMs: Long = CONSUMED_RETENTION_MS,
    ): Int

    companion object {
        /**
         * How long a consumed hash keeps rejecting re-posts after its serving
         * expiry. Any value comfortably past the 5-minute pairing window works
         * (a replayed post after eviction stores a blob nobody polls); 24 h
         * matches the other mailbox sweeps' cadence.
         */
        const val CONSUMED_RETENTION_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
