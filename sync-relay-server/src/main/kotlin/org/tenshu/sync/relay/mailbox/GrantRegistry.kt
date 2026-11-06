package org.tenshu.sync.relay.mailbox

/**
 * Relay-side state for the **shared-realm membership grant mailbox**
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D3): per grant address,
 * routing metadata (`address → ownerId, expiry`) plus one **sealed** grant blob.
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md` pattern). Two backends:
 *   - [GrantStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteGrantStore` — durable.
 *
 * Zero-trust posture (House Rule #1): the [address] is an HKDF of the contact
 * handshake's shared secret (`deriveGrantAddress`), derivable only by grantor +
 * grantee, and the blob is sealed to the grantee's A13 — the relay learns
 * neither party's `ownerId` from routing nor anything from the ciphertext. The
 * mailbox is **single-pickup** (delete on read, D3) with a TTL: whichever
 * grantee device polls first does the Accept and writes the membership row,
 * which then syncs to the grantee's other devices via same-user auto-join.
 */
interface GrantRegistry : OwnerScopedStore {

    /** Outcome of `POST /sync/grant/{address}`. */
    enum class PutResult {
        /** Stored (or replaced a prior unread grant at the same address — a re-grant). */
        STORED,

        /** The grantor is at the open-grant cap — 429. */
        CAP_EXCEEDED,
    }

    /** Outcome of `GET /sync/grant/{address}` — a successful pickup deletes the record. */
    sealed interface Pickup {
        /** The sealed grant — 200; the record is deleted (single-pickup). */
        data class Found(val sealedGrant: ByteArray) : Pickup

        /** No grant at this address, or it has expired — 404. */
        data object Empty : Pickup
    }

    /**
     * Drop a sealed grant at [address] for grantor [ownerId]. **Last-write-wins**
     * on a repeated address — a re-grant (Kr rotation) supersedes an unread
     * earlier blob and does not consume a fresh slot. [maxOpenPerOwner] caps the
     * grantor's live (unexpired) grants — checked against new addresses only.
     */
    fun put(
        address: String,
        ownerId: String,
        sealedGrant: ByteArray,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxOpenPerOwner: Int,
    ): PutResult

    /**
     * Pick up + delete the grant at [address]. Returns [Pickup.Empty] for an
     * absent or expired record (and drops an expired one in passing).
     */
    fun pickup(address: String, nowMs: Long = System.currentTimeMillis()): Pickup

    /** Drop records past their TTL. Returns the count dropped. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int

    /** Number of records currently held (test/diagnostics hook). */
    fun recordCount(): Int
}
