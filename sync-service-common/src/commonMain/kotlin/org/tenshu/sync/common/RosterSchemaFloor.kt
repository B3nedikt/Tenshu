package org.tenshu.sync.common

import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap

/**
 * How far history may be cut off, judged from the device roster (E4,
 * `core-schema-evolution.md` §8.3: *"the floor for service S advances to F only
 * when every registered, non-revoked device has recorded a supported version
 * ≥ F"*).
 *
 * Pure and total: it takes the roster and the recorded statements and returns a
 * verdict. It does **not** read the store, advance anything, or decide when to
 * compact — the point of keeping it a function is that the rule below is worth
 * testing on its own, and the scheduling around it never is.
 */
object RosterSchemaFloor {

    /**
     * The verdict for one realm.
     *
     * ⚠ [Unknown] is not a failure and must not be treated as "0" or skipped: it
     * is the answer whenever any device in the roster has not stated what it
     * runs, and the only safe response is to leave the floor where it is.
     */
    sealed interface Verdict {

        /**
         * Every roster device has spoken. [perService] is the MIN across them —
         * the highest floor that is safe for **all** of them — and [coreVersion]
         * the same for `:core`.
         *
         * ⚠ A service absent from [perService] is one *no device runs*, which is
         * not the same as "everyone is at 0": it carries no constraint at all,
         * so a caller must not read a missing entry as a floor of zero.
         */
        data class Advanceable(
            val coreVersion: Int,
            val perService: Map<String, Int>,
        ) : Verdict

        /**
         * At least one registered, non-revoked device has no recorded version.
         * [silentDeviceIds] names them — the actionable half, since the user's
         * remedy is to open the app on that device or revoke it (ADR 0007).
         */
        data class Unknown(val silentDeviceIds: Set<String>) : Verdict
    }

    /**
     * @param rosterDeviceIds every registered, **non-revoked** device. Revocation
     *   filtering happens before this call: a revoked device is gone from the
     *   roster and therefore stops holding the floor back, which is exactly the
     *   lever ADR 0007 already gives the user for a device that will never return.
     * @param recorded each device's stated vector, keyed by device id. Devices
     *   present here but absent from [rosterDeviceIds] are ignored — a stale row
     *   from a revoked device must never *lower* the floor.
     */
    fun evaluate(
        rosterDeviceIds: Set<String>,
        recorded: Map<String, VersionVector>,
    ): Verdict {
        val silent = rosterDeviceIds.filterNot { recorded.containsKey(it) }.toSet()
        if (silent.isNotEmpty()) return Verdict.Unknown(silent)
        if (rosterDeviceIds.isEmpty()) return Verdict.Unknown(emptySet())

        val vectors = rosterDeviceIds.mapNotNull { recorded[it] }
        val core = vectors.minOf { it.coreVersion.get().toInt() }

        // ⚠ MIN over the devices that RUN a service, but a service only
        // constrains the floor if EVERY device runs it: a device that does not
        // know service S cannot read S's history at any version, so S's floor
        // cannot be justified by the devices that do know it. Absent ⇒ omitted,
        // never defaulted to 0 (see Advanceable.perService).
        val perService = mutableMapOf<String, Int>()
        val serviceIds = vectors.first().toMap().keys
        for (serviceId in serviceIds) {
            val versions = vectors.map { it.toMap()[serviceId] }
            if (versions.any { it == null }) continue
            perService[serviceId] = versions.filterNotNull().min()
        }

        return Verdict.Advanceable(coreVersion = core, perService = perService)
    }
}
