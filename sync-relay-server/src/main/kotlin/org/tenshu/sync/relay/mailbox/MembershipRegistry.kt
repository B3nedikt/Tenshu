package org.tenshu.sync.relay.mailbox

/**
 * Relay-side durable state for cross-user shared-realm membership
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D1/D6):
 *
 * - a per-realm **revocation list** (append-only) the admit path checks, and
 * - the **admitted-device associations** `(realmId, memberAuthorityPub) →
 *   deviceIds` recorded at admit time so a revocation can purge exactly the
 *   right device registrations from `KnownDevicesStore`.
 *
 * Storage SPI (`sync-relay-persistence.md` pattern). Two backends:
 *   - [MembershipStore] — in-memory; default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteMembershipStore` — durable.
 *
 * **Durability matters here** unlike the ephemeral grant mailbox: losing the
 * revocation list on restart would silently re-admit a revoked member's
 * devices. Zero-trust (House Rule #1): every column is a public key, an opaque
 * id, or a master-signed token — no plaintext, no realm key.
 */
interface MembershipRegistry : OwnerScopedStore {

    /** One realm-master-signed revocation of a member authority (A26). */
    data class RevocationRecord(
        val realmId: String,
        val memberAuthorityPublicKey: ByteArray,
        val epoch: Long,
        val revokedAt: Long,
        val signature: ByteArray,
    )

    /**
     * Record that a device with id [deviceId] was admitted under member
     * authority [memberAuthorityPublicKey] for realm [realmId] — so a later
     * revocation of that authority can purge it. Idempotent.
     */
    fun recordAdmission(realmId: String, memberAuthorityPublicKey: ByteArray, deviceId: String)

    /** Device ids admitted under `(realmId, memberAuthorityPublicKey)`. */
    fun admittedDevices(realmId: String, memberAuthorityPublicKey: ByteArray): Set<String>

    /** Forget the admission associations for `(realmId, memberAuthorityPublicKey)` (post-purge). */
    fun clearAdmissions(realmId: String, memberAuthorityPublicKey: ByteArray)

    /**
     * Append a master-signed revocation. The caller verifies the master
     * signature against the realm's `masterPublicKey` **before** appending;
     * the store is a plain ledger.
     */
    fun appendRevocation(record: RevocationRecord)

    /** The revocation list for [realmId] (empty if none). */
    fun revocations(realmId: String): List<RevocationRecord>

    /** Total revocation records held (test/diagnostics hook). */
    fun recordCount(): Int
}
