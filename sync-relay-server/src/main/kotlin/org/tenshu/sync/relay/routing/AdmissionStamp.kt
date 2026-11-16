package org.tenshu.sync.relay.routing

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.common.util.decodeHlcStamp

/**
 * The admission stamp a registering route records (`DeviceRegistry.register(admittedAt)`), from the
 * client-asserted `admittedAtHlc` — `null` when absent or malformed, as before.
 *
 * ⚠⚠ **A stamp from the future is clamped to the relay's clock** (`sync-entity-identity.md` §5b R3).
 * The stamp is what `revokeIfNotStale` compares a removal against, and a removal authored *before* it
 * is stale. So a far-future stamp would make every later, honest removal of that device stale — an
 * admission nobody can revoke through `/sync/device-access/revoke`. On the member-admit route the
 * **member itself** writes the stamp, so without this the stamp R3 adds would be a way for a member
 * device to make itself unremovable by its owner. Clamping, rather than refusing, keeps a client with
 * a fast clock registrable; the clamped value is still "at or after the admission", which is all the
 * ordering rule needs.
 *
 * ⚠ Understating stays possible and harmless: it only makes the device easier to revoke.
 */
internal fun admissionStamp(raw: String?, nowEpochMillis: Long = System.currentTimeMillis()): HlcStamp? {
    val stamp = decodeHlcStamp(raw) ?: return null
    return if (stamp.physicalTime > nowEpochMillis) {
        HlcStamp(physicalTime = nowEpochMillis, logicalCounter = 0L, deviceId = stamp.deviceId)
    } else {
        stamp
    }
}
