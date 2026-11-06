package org.tenshu.sync.relay.auth

import org.tenshu.core.common.util.HlcStamp

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-owner device-key registry used to verify JWT signatures.
 *
 * After `sync-relay-api.md` lands, devices are admitted via:
 *   - `POST /sync/realm` (first device, master-self-signed),
 *   - `POST /sync/pairing/response` (master-delegated by an existing realm device),
 *   - `POST /sync/recovery-claim` (master-signed challenge response).
 *
 * Each path stores `(ownerId, deviceId) → Ed25519 publicKey` here. Subsequent JWT
 * verification looks up the public key by `(iss, sub)` and rejects when no entry exists.
 *
 * In-memory [DeviceRegistry] backend — the default + test backend. The durable backend
 * is `org.tenshu.sync.relay.storage.SqliteDeviceRegistry`
 * (`docs/proposals/sync-relay-persistence.md`).
 */
class KnownDevicesStore : DeviceRegistry {

    private val store = ConcurrentHashMap<DeviceKey, ByteArray>()

    /** The HLC each admission was authored at, when the admitter sent one. See [DeviceRegistry]. */
    private val admittedAt = ConcurrentHashMap<DeviceKey, HlcStamp>()

    data class DeviceKey(val ownerId: String, val deviceId: String)

    /** Register or replace the public key for [ownerId]/[deviceId]. */
    override fun register(
        ownerId: String,
        deviceId: String,
        publicKey: ByteArray,
        admittedAt: HlcStamp?,
    ) {
        val key = DeviceKey(ownerId, deviceId)
        store[key] = publicKey
        // ⚠ A re-admission without a stamp CLEARS the old one rather than keeping it: the stale
        // value describes an admission this one has superseded, and a stamp that outlives the
        // admission it belongs to would let the guard reject a legitimate later revocation.
        if (admittedAt != null) this.admittedAt[key] = admittedAt else this.admittedAt.remove(key)
    }

    override fun admittedAt(ownerId: String, deviceId: String): HlcStamp? =
        admittedAt[DeviceKey(ownerId, deviceId)]

    /** Returns the registered public key, or null if the device is unknown. */
    override fun getPublicKey(ownerId: String, deviceId: String): ByteArray? =
        store[DeviceKey(ownerId, deviceId)]

    /**
     * Returns the set of admitted deviceIds for [ownerId] — i.e. the relay's
     * authoritative view of "which devices are currently in this realm." Used by
     * `SyncMailbox.gcSweep` to compute the quorum a tombstone has to clear before
     * its target body can be GC'd (per `docs/adr/0006-sync-tombstones.md`).
     */
    override fun deviceIds(ownerId: String): Set<String> =
        store.keys.asSequence()
            .filter { it.ownerId == ownerId }
            .map { it.deviceId }
            .toSet()

    /**
     * Revoke a device — removes its entry so subsequent `getPublicKey` returns null
     * and the `JwtAuthenticator` rejects with 401. Called by the relay's
     * `POST /sync/push-tombstone` handler when it receives a `Device`-targeted
     * `DeleteBlock` carrying a `revokedDeviceId` (T-K-02 enforcement per
     * `docs/sync/threat-model.md` §8.5). Idempotent — safe to call repeatedly.
     */
    override fun revoke(ownerId: String, deviceId: String) {
        val key = DeviceKey(ownerId, deviceId)
        store.remove(key)
        admittedAt.remove(key)
    }

    // F2′ — see DeviceRegistry.recordRemoval. Separate from `store`, deliberately: this outlives
    // the registration it describes, which is the entire point.
    private val removed = mutableSetOf<DeviceKey>()

    override fun recordRemoval(ownerId: String, deviceId: String) {
        removed += DeviceKey(ownerId, deviceId)
    }

    override fun wasRemoved(ownerId: String, deviceId: String): Boolean =
        DeviceKey(ownerId, deviceId) in removed

    /** Account closure (F12): registrations and the removal ledger, as `SqliteDeviceRegistry` — see [DeviceRegistry.eraseOwner]. */
    override fun eraseOwner(ownerId: String): Int {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        val keys = store.keys.filter { it.ownerId == ownerId }
        keys.forEach { store.remove(it); admittedAt.remove(it) }
        val removedKeys = removed.filter { it.ownerId == ownerId }
        removed.removeAll(removedKeys.toSet())
        return keys.size + removedKeys.size
    }

    /** Test/maintenance helper. Reset to empty. */
    fun clear() {
        store.clear()
        admittedAt.clear()
        removed.clear()
    }

    fun size(): Int = store.size
}
