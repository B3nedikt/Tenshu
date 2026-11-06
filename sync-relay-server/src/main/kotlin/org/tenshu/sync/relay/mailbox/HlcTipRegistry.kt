package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.SignedHlcTip

/**
 * The relay-side per-device HLC-tip registry: `(ownerId, deviceId) → latest SignedHlcTip`.
 * Read to assemble `peerTips` on `/sync/changes` (the I6 no-silent-censorship surface) and
 * by the tombstone-GC ack quorum.
 *
 * Storage SPI (see `docs/proposals/sync-relay-persistence.md`, Phase 2b). Two
 * interchangeable backends select at startup by config:
 *   - [HlcTipStore] — in-memory `ConcurrentHashMap`; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteHlcTipStore` — durable; serialises the
 *     `SignedHlcTip` wire type via `RelayBinaryCodec`.
 *
 * Tips are **signed metadata**, not plaintext — persisting them is House-Rule-#1-safe.
 * (`clear` is a test-only helper on the in-memory backend, not part of the SPI.)
 */
interface HlcTipRegistry : OwnerScopedStore {

    /** Record [tip] as the latest for `(ownerId, tip.deviceId)`, overwriting any prior. */
    fun record(ownerId: String, tip: SignedHlcTip)

    /** Every realm device's latest tip *except* [excludingDeviceId] (the requester's own). */
    fun getPeerTips(ownerId: String, excludingDeviceId: String): List<SignedHlcTip>

    /** Every recorded tip for [ownerId] (including the requester's) — used by GC quorum. */
    fun allTips(ownerId: String): List<SignedHlcTip>

    /**
     * Drop [deviceId]'s tip under [ownerId]; returns the rows removed (0 or 1). Called when the device
     * is revoked (`DeviceRevocation`, `relay-metadata-minimisation.md` F11 / Phase 4 (b)) — a revoked
     * device's last wall-clock observation otherwise stays at rest and keeps being served as a peer tip.
     */
    fun forgetDevice(ownerId: String, deviceId: String): Int
}
