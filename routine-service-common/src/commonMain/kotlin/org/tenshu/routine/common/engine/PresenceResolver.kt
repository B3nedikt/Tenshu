package org.tenshu.routine.common.engine

import org.tenshu.routine.common.program.Occupancy

/** One device's reported presence, already read off its row. */
class DevicePresence(
    val deviceId: String,
    /** `PresenceState.INSIDE` | `OUTSIDE` | `UNKNOWN`. */
    val state: String,
    val freshAsOfEpochMs: Long,
)

/**
 * Turns per-device presence rows into the single [Occupancy] a routine condition
 * asks about (`routines-engine.md` RE-D7 / RE-4).
 *
 * ⚠⚠ **Fail-closed is the whole design, not a defensive touch.** The naive
 * implementation fails *open* — it treats "no signal" as "not home" — and
 * "arm the alarm when nobody's home" firing because someone's phone battery died
 * is the worst possible failure mode of this entire engine. So:
 *
 *  - any contributing device that is `UNKNOWN`, or whose row is older than the
 *    staleness window, makes the whole verdict `null` (= `Unknown`), and
 *  - the caller then **skips the routine** rather than running it.
 *
 * ⚠ **What counts as a contributing device, and the limit that follows.** Only
 * devices that have *ever* written a row — the honest definition of
 * "location-capable" available without a new capability field on `Device`
 * (the same gap RE-3's tier derivation hit). A phone with location turned off
 * therefore never contributes, so its presence at home cannot block a
 * nobody's-home verdict.
 *
 * ⚖️ That must be reflected in the **copy**, not hidden: the condition means
 * *"when none of the devices you've given location to is home"*. Which is also
 * the honest answer to OQ-3 — the realm is one user's devices, so "nobody's
 * home" has always really meant "none of *my* devices is home"; a guest's phone
 * was never in the realm to begin with.
 *
 * ⚠ Hubs are excluded: an always-on box sitting in the hall is definitionally
 * always "home" and would make a nobody's-home condition permanently false.
 */
class PresenceResolver(
    private val staleWindowMs: Long,
) {

    /**
     * @param contributors presence rows, one per reporting device.
     * @param excludedDeviceIds devices that must not contribute — hubs, and any
     *   revoked device (a revoked phone's last known position must not keep
     *   deciding what happens in the house).
     * @return the household verdict, or `null` when it cannot be established.
     */
    fun resolve(
        contributors: List<DevicePresence>,
        excludedDeviceIds: Set<String>,
        nowEpochMs: Long,
    ): Occupancy? {
        val relevant = contributors.filterNot { it.deviceId in excludedDeviceIds }

        // ⚠ No contributors at all is UNKNOWN, not "nobody home". A household
        // that has never enabled location has not told us the house is empty.
        if (relevant.isEmpty()) return null

        relevant.forEach { presence ->
            if (presence.state == STATE_UNKNOWN) return null
            if (nowEpochMs - presence.freshAsOfEpochMs > staleWindowMs) return null
            if (presence.state != STATE_INSIDE && presence.state != STATE_OUTSIDE) return null
        }

        return if (relevant.any { it.state == STATE_INSIDE }) {
            Occupancy.SOMEBODY_HOME
        } else {
            Occupancy.NOBODY_HOME
        }
    }

    private companion object {
        // Mirrors PresenceState's constants without depending on the schema
        // module — this class is pure and lives beside the other pure evaluators.
        const val STATE_INSIDE = "inside"
        const val STATE_OUTSIDE = "outside"
        const val STATE_UNKNOWN = "unknown"
    }
}
