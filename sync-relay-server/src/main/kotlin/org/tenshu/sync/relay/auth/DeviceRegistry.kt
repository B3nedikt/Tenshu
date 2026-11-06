package org.tenshu.sync.relay.auth

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.OwnerScopedStore

/**
 * The relay-side device-key registry: `(ownerId, deviceId) → Ed25519 public key`, the
 * trust anchor JWT verification looks up by `(iss, sub)`.
 *
 * Storage SPI (see `docs/proposals/sync-relay-persistence.md`). Two interchangeable
 * backends select at startup by config:
 *   - [KnownDevicesStore] — in-memory `ConcurrentHashMap`; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteDeviceRegistry` — durable; survives restart.
 *
 * Only public keys are stored — never plaintext — so persistence does not widen the
 * zero-trust surface (House Rule #1). Test/maintenance helpers (`clear`, `size`) live on
 * the in-memory impl, not here.
 */
interface DeviceRegistry : OwnerScopedStore {

    /**
     * Register or replace the public key for [ownerId]/[deviceId].
     *
     * [admittedAt] is the HLC the *admitting* client authored this admission at, when it sent
     * one. It exists so [admittedAt] can be compared against an arriving `Device` tombstone's
     * HLC — see [revoke]. `null` means the admitter did not say — not an older client but a
     * current caller that carries no stamp: recovery re-admission, a shared-realm membership grant,
     * and `SharedRealmDeviceAdmitter` on `/sync/admit-device`. It reads as "unknown" and leaves
     * [revoke] behaving as it always has.
     */
    fun register(
        ownerId: String,
        deviceId: String,
        publicKey: ByteArray,
        admittedAt: HlcStamp? = null,
    )

    /**
     * The stamp recorded by [register], or `null` when this device was admitted without one
     * (or is not registered at all).
     */
    fun admittedAt(ownerId: String, deviceId: String): HlcStamp? = null

    /** Returns the registered public key, or null if the device is unknown. */
    fun getPublicKey(ownerId: String, deviceId: String): ByteArray?

    /**
     * The set of admitted deviceIds for [ownerId] — the relay's authoritative view of
     * "which devices are currently in this realm." Used by `SyncMailbox.gcSweep` to
     * compute the tombstone-ack quorum.
     */
    fun deviceIds(ownerId: String): Set<String>

    /**
     * Revoke a device — removes its entry so subsequent [getPublicKey] returns null and
     * `JwtAuthenticator` rejects with 401. Idempotent.
     *
     * ⚠⚠ Callers applying a `Device` **tombstone** must go through [revokeIfNotStale], not this:
     * a tombstone carries the HLC it was *authored* at, and delivery order is not authorship
     * order. A retirement that could not be pushed for hours (401/402) otherwise executes
     * against an admission that happened in the meantime.
     */
    fun revoke(ownerId: String, deviceId: String)

    /**
     * Apply a `Device` tombstone: revoke [deviceId] **unless** the tombstone was authored
     * before the admission currently on record. Returns true when the revocation was applied.
     *
     * The ordering rule is the clients' (`SyncProcessor.resolveConflictAndSave`): a tombstone
     * dominates anything at or before its own HLC, and an admission strictly *newer* than the
     * tombstone survives as a recreate. An absent stamp on either side means "unknown" and the
     * revocation applies — which is exactly the behaviour before this existed.
     */
    fun revokeIfNotStale(
        ownerId: String,
        deviceId: String,
        tombstoneHlc: HlcStamp?,
    ): Boolean {
        val admittedAt = admittedAt(ownerId, deviceId)
        if (tombstoneHlc != null && admittedAt != null && tombstoneHlc < admittedAt) {
            return false
        }
        revoke(ownerId, deviceId)
        // F2′: record the removal durably. `revoke` is a hard DELETE, so without this the relay
        // cannot answer "has this deviceId been removed before?" — the question option F's
        // never-return rule asks at admit time.
        recordRemoval(ownerId, deviceId)
        return true
    }

    /**
     * Durably record that [deviceId] was **removed from [ownerId]'s realm by its owner**
     * (`admission-revocation-names-the-capability.md` §7c).
     *
     * ⚠⚠ Called from [revokeIfNotStale] ONLY — i.e. from an applied `Device` tombstone, which is
     * the one signal that means "the user removed this device". It must **not** be called from
     * [revoke] directly: `MembershipGrantRouting` calls that for shared-realm *member* removal,
     * which is a different fact. Recording both would repeat the exact conflation that made §7's
     * F2 wrong — one ledger holding two populations that need opposite treatment.
     *
     * ⚠ The ledger can never be garbage-collected: deleting a row re-admits that identity, which is
     * the one thing option F promises not to do. One row per removal, so it is unbounded by
     * construction but trivially small (§7c.4).
     */
    fun recordRemoval(ownerId: String, deviceId: String)

    /** True if [deviceId] was previously removed from [ownerId]'s realm. */
    fun wasRemoved(ownerId: String, deviceId: String): Boolean

    /**
     * Account closure (F12): drop every **registration** (and admission stamp) of [ownerId], **and**
     * its [recordRemoval] ledger.
     *
     * ⚠⚠ The ledger may go here, and only here, because closure also writes the `erased_realm`
     * tombstone (`RealmRegistry.isErased`): the realm can never be re-created, so a removed device's
     * master delegation has no realm to verify into. That is the one exception to "never GC'd"
     * above. See `AccountErasure`'s table.
     */
    override fun eraseOwner(ownerId: String): Int
}
