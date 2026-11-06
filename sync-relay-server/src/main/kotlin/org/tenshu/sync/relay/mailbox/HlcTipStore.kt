package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.SignedHlcTip
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-owner registry of each device's latest signed HLC tip. Populated on every
 * `POST /sync/changes` request (the requesting device's tip), read to assemble
 * the response's `peerTips` list (every other device's last-known tip).
 *
 * Per `docs/proposals/sync-backend-relay-plan.md` §5.1 + `docs/sync/threat-model.md`
 * §11.3: this is what makes I6 (no silent censorship) enforceable. A relay serving
 * a forked view ends up with peer tips that disagree across the devices it's lying
 * to — receivers compare those signed tips against their own observation history
 * and surface a warning.
 *
 * Concurrency: outer + inner maps are `ConcurrentHashMap`. The inner map's
 * `computeIfAbsent` ensures exactly-once init per owner; per-device `put` is
 * already atomic. No external lock needed.
 *
 * In-memory [HlcTipRegistry] backend — the default + test backend. The durable backend is
 * `org.tenshu.sync.relay.storage.SqliteHlcTipStore`
 * (`docs/proposals/sync-relay-persistence.md`, Phase 2b).
 */
class HlcTipStore : HlcTipRegistry {

    // ownerId -> deviceId -> latest tip
    private val tipsByOwner = ConcurrentHashMap<String, ConcurrentHashMap<String, SignedHlcTip>>()

    /**
     * Records [tip] as the latest known tip for `(ownerId, tip.deviceId)`. Overwrites
     * any previously stored tip for that device — the relay always serves whatever
     * the device last claimed, even if it regressed (regression detection happens on
     * the receiver client side, not server-side).
     */
    override fun record(ownerId: String, tip: SignedHlcTip) {
        val perDevice = tipsByOwner.computeIfAbsent(ownerId) { ConcurrentHashMap() }
        perDevice[tip.deviceId.get()] = tip
    }

    /**
     * Returns every realm device's latest tip *except* [excludingDeviceId] — used to
     * build the response's `peerTips` list (the requester doesn't need its own tip
     * echoed back). Returns an empty list if no tips have been recorded for [ownerId].
     */
    override fun getPeerTips(ownerId: String, excludingDeviceId: String): List<SignedHlcTip> {
        val perDevice = tipsByOwner[ownerId] ?: return emptyList()
        return perDevice.entries
            .filter { it.key != excludingDeviceId }
            .map { it.value }
    }

    /** Test helper — returns every recorded tip for an owner (including the requester's). */
    override fun allTips(ownerId: String): List<SignedHlcTip> =
        tipsByOwner[ownerId]?.values?.toList().orEmpty()

    override fun forgetDevice(ownerId: String, deviceId: String): Int =
        if (tipsByOwner[ownerId]?.remove(deviceId) != null) 1 else 0

    /** Account closure (F12). */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        return tipsByOwner.remove(ownerId)?.size ?: 0
    }

    fun clear() {
        tipsByOwner.clear()
    }
}
