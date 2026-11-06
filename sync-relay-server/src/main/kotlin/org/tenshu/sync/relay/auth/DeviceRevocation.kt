package org.tenshu.sync.relay.auth

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.HlcTipRegistry

/**
 * Revoking a device, and forgetting what the relay held about its sync progress in the same step
 * (`relay-metadata-minimisation.md` F11).
 *
 * Without the prune, a revoked device's `hlc_tip` (its last signed wall-clock observation) would
 * stay at rest for ever and keep being served to every peer as `peerTips`. Nothing needs it once
 * the device is gone:
 *
 * - **Tombstone-GC quorum** (`MailboxStore.acknowledgementSet`) iterates
 *   [DeviceRegistry.deviceIds] and only *looks up* tips, so a revoked device's tip is ignored there
 *   — pruning changes nothing.
 * - **`HlcDivergenceTracker`** (client) is the only consumer of `peerTips`. A revoked device's
 *   `Device` row is tombstoned locally, so its tip goes unverified (no key) and, being frozen, never
 *   regresses. An absent tip is never a warning, so pruning only stops a dead entry being re-read.
 *
 * ⚠⚠ **A revoked device's in-flight request can race the revocation** — it passed JWT auth before
 * the revoke and records its tip after the prune. [forgetIfUnregistered] is the guard: the
 * routes call it *after* writing, so whichever order the two land in, the row ends up gone (the
 * revoke deletes the registration first, then the rows; a late writer re-checks the registration
 * after its write and deletes its own row).
 *
 * [transaction] runs a revocation as one unit: `RelayDatabase.inTransaction` on a SQLite relay (the
 * registration, the removal ledger and the tip commit together), identity in memory.
 */
class DeviceRevocation(
    private val knownDevices: DeviceRegistry,
    private val hlcTipStore: HlcTipRegistry,
    /**
     * The newest admission stamp **queued but not yet delivered** for `(ownerId, deviceId)` — both
     * pairing flows register a new device only when it fetches its response (`PairingResponseInbox`,
     * `PairingOfferRegistry.OfferResponse.registration`), so until then its admission's HLC lives
     * only there. ⚠⚠ Without this a `Device` tombstone authored *before* that admission but
     * delivered inside the window would find nothing on record, apply, write the removal ledger, and
     * the delivery would then refuse the device for good. Default: nothing pending.
     */
    private val pendingAdmittedAt: (ownerId: String, deviceId: String) -> HlcStamp? = { _, _ -> null },
    // Last, so a trailing lambda at the call site stays the transaction.
    private val transaction: (() -> Unit) -> Unit = { it() },
) {

    /**
     * Apply a `Device` tombstone ([DeviceRegistry.revokeIfNotStale]) and, **only when it applied**,
     * forget the device's tip. A stale tombstone (authored before the admission on record)
     * revokes nothing, so it must prune nothing — the device is still in the realm.
     */
    fun applyDeviceTombstone(ownerId: String, deviceId: String, tombstoneHlc: HlcStamp?): Boolean {
        // The same ordering rule as [DeviceRegistry.revokeIfNotStale], against a queued admission:
        // strictly newer survives; an absent stamp on either side lets the revocation apply.
        val pending = pendingAdmittedAt(ownerId, deviceId)
        if (tombstoneHlc != null && pending != null && tombstoneHlc < pending) return false
        var applied = false
        transaction {
            applied = knownDevices.revokeIfNotStale(ownerId, deviceId, tombstoneHlc)
            if (applied) forget(ownerId, deviceId)
        }
        return applied
    }

    /** Shared-realm member removal: [DeviceRegistry.revoke] (no removal-ledger entry) plus the prune. */
    fun revokeMemberDevice(realmId: String, deviceId: String) {
        transaction {
            knownDevices.revoke(realmId, deviceId)
            forget(realmId, deviceId)
        }
    }

    /**
     * The late-write guard: if [deviceId] is no longer registered under [ownerId], drop the tip it
     * may just have written and return true (the caller answers 401). Call it **after** the
     * write — before would leave the race open.
     */
    fun forgetIfUnregistered(ownerId: String, deviceId: String): Boolean {
        if (knownDevices.getPublicKey(ownerId, deviceId) != null) return false
        forget(ownerId, deviceId)
        return true
    }

    private fun forget(ownerId: String, deviceId: String) {
        hlcTipStore.forgetDevice(ownerId, deviceId)
    }
}
