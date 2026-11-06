package org.tenshu.sync.relay.mailbox

/**
 * Relay-side durable state for delegated device-admission authority
 * (`docs/proposals/delegated-device-admission-authority.md` §5, P2): the
 * per-owner **admission revocation list** (append-only) that the chain-verified
 * `POST /sync/pairing/response` path checks — a revoked device's A33 capability
 * (and every chain running through it) stops admitting.
 *
 * Storage SPI (`sync-relay-persistence.md` pattern). Two backends:
 *   - [AdmissionRevocationStore] — in-memory; default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteAdmissionRevocationStore` — durable.
 *
 * **Durability matters here** (the `member_revocation` posture, not the
 * ephemeral-mailbox one): losing the list on restart would silently restore a
 * revoked device's power to admit. Zero-trust (House Rule #1): every column is
 * an opaque id, an epoch, or a master-signed token — no plaintext, no realm key.
 */
interface AdmissionRevocationRegistry : OwnerScopedStore {

    /** One realm-master-signed revocation of a device's admission capability (A33). */
    data class RevocationRecord(
        val ownerId: String,
        val deviceId: String,
        val epoch: Long,
        val revokedAt: Long,
        val signature: ByteArray,
        /**
         * The A33 capability key this revocation names (F1′) — **required** since F5b
         * (`admission-revocation-names-the-capability.md` §7g); the keyless device-at-an-epoch form
         * is gone. ⚠ House Rule #1 is unaffected — a public key the relay already sees in every
         * admission chain, stored opaquely and never used to mint anything.
         */
        val deviceA33PublicKey: ByteArray,
    )

    /**
     * Append a master-signed revocation. The caller verifies the master
     * signature against the realm's `masterPublicKey` **before** appending;
     * the store is a plain ledger.
     */
    fun appendRevocation(record: RevocationRecord)

    /** The revocation list for [ownerId]'s realm (empty if none). */
    fun revocations(ownerId: String): List<RevocationRecord>

    /** Total revocation records held (test/diagnostics hook). */
    fun recordCount(): Int
}
