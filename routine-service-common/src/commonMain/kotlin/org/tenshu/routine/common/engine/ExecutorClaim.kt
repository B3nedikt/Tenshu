package org.tenshu.routine.common.engine

import org.tenshu.routine.common.program.Trigger

/**
 * Capability tiers, **in priority order** (`routines-engine.md` RE-D2). A device
 * that publishes a `HubStatus` row is always-on, resident and punctual; a
 * desktop is resident but sleeps; a phone can promise nothing.
 */
enum class CapabilityTier { ALWAYS_ON, RESIDENT, MOBILE }

class RosterDevice(
    val deviceId: String,
    val tier: CapabilityTier,
    val revoked: Boolean = false,
)

/**
 * RE-D1: can *this* process observe this trigger class at all?
 *
 * ⭐ This is the whole multi-device design in one predicate. Three of the four
 * trigger classes have exactly one eligible observer by construction, so the
 * engine on a given device simply does not arm what it cannot see — no
 * negotiation, no configuration, no per-device routine-assignment UI.
 */
fun interface TriggerObservability {
    fun canObserve(trigger: Trigger): Boolean
}

/**
 * The default predicate, parameterised by what this process actually has.
 *
 * @param hostsDeviceBridge true only in a process that hosts a `DeviceBridge`
 *   (the hub). A remote observer sees a device change as a `DeviceState` row at
 *   the HD-D2 3–5 s poll cadence, which is useless for motion→light.
 * @param hasLocationSource true only on a device with a location capture source.
 *   ⚖️ A hub can *never* fire a geofence; only the phone that crossed knows.
 */
class DefaultTriggerObservability(
    private val hostsDeviceBridge: Boolean,
    private val hasLocationSource: Boolean,
) : TriggerObservability {

    override fun canObserve(trigger: Trigger): Boolean = when (trigger) {
        is Trigger.TimeOfDay, is Trigger.Sun -> true
        is Trigger.DeviceEvent -> hostsDeviceBridge
        is Trigger.Geofence -> hasLocationSource
        // Arrives as an explicit invocation on the device that heard it, never
        // as a background evaluation — so nothing arms it.
        is Trigger.VoicePhrase -> false
    }
}

/**
 * RE-D2's deterministic claim for the one contended trigger class (time/sun).
 *
 * ⚠⚠ **This takes no clock, and must not.** A time-varying claim would let two
 * devices disagree across a tick, which is precisely what the determinism exists
 * to prevent. The 90-second grace fallback is applied by the *caller* — that is
 * where time belongs.
 *
 * ⚠⚠ A synced receipt cannot prevent a race, only record one: the sync round
 * trip is longer than the action it would suppress. Mutual exclusion is
 * **assignment, not agreement**, which is what this function provides.
 */
fun interface ExecutorClaim {
    /** @return the winning `deviceId`, or `null` when the roster has no eligible device. */
    fun executorFor(routineId: String, roster: List<RosterDevice>): String?
}

/**
 * Capability tier first, then a stable tiebreak on `deviceId`. Pure, offline,
 * message-free, and identical on every device that has converged on the roster.
 *
 * ⚖️ Rendezvous hashing (spreading routines across peers) was considered and
 * rejected as premature: a household has tens of routines, not thousands, and a
 * stable single executor makes run history far easier to read.
 *
 * ⚠ A device that goes offline permanently keeps winning until it is revoked,
 * and its routines then only fire on the grace fallback — a mild, visible,
 * self-explaining degradation ("everything runs 90 s late") whose fix, ADR 0007
 * revocation, the user already holds. Deliberately no liveness heartbeat: a new
 * synced hot row with a staleness window to tune, to buy 90 seconds.
 */
class TierThenIdExecutorClaim : ExecutorClaim {

    override fun executorFor(routineId: String, roster: List<RosterDevice>): String? =
        roster.asSequence()
            .filterNot { it.revoked }
            .sortedWith(compareBy({ it.tier.ordinal }, { it.deviceId }))
            .firstOrNull()
            ?.deviceId
}
