package org.tenshu.routine.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.RosterDevice

/**
 * Assembles a [RoutineEngine] with its arming wired.
 *
 * The engine is deliberately cheap to build (it holds no state between wakes —
 * the journal and the run rows *are* the state), so each op invocation gets a
 * fresh one rather than sharing a long-lived instance across a process that may
 * be head-less.
 *
 * ⚠⚠ **Exception, and the reason this object holds state at all: the
 * `SafetyGate` is process-scoped.** Its token bucket is in-memory, so a
 * per-invocation gate forgets throttling between calls. That was survivable in
 * RE-1b (a scheduled wake fires one routine at most once per trigger instant)
 * but is *not* survivable now that RE-2 delivers device events: a flapping
 * sensor drives many firings inside one process lifetime, and a gate that
 * forgets between them would let every one through — the runaway rail would be
 * decorative, and RE-D9's "throttle is also a sync brake" claim would be false.
 *
 * ⚠ The gate is therefore shared across every engine this factory builds in the
 * process. One proxy serves one realm, so shared buckets are correct; if a
 * process ever hosted several realms this would need keying by realm.
 */
internal object RoutineEngineFactory {

    /**
     * ⚠⚠ Process-scoped by construction (class KDoc). Deliberately NOT a
     * parameter with a default — a caller that passed a fresh gate would
     * silently disable the runaway rail, which is exactly the bug this slice
     * exists to prevent.
     */
    private val processSafetyGate = DefaultSafetyGate()

    fun create(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        nowEpochMs: () -> Long,
        thisDeviceId: () -> String?,
        roster: () -> List<RosterDevice>,
        hostsDeviceBridge: Boolean,
    ): RoutineEngine = RoutineEngine(
        executionContext = executionContext,
        implementationFactory = implementationFactory,
        nowEpochMs = nowEpochMs,
        thisDeviceId = thisDeviceId,
        roster = roster,
        hostsDeviceBridge = hostsDeviceBridge,
        safetyGate = processSafetyGate,
    ).also { engine ->
        engine.armer = RoutineScheduleArmer(
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            nowEpochMs = nowEpochMs,
        )::invoke
    }

    /**
     * The real roster (RE-3): every authorised `Device` in the realm, with hubs
     * marked always-on. Falls back to [singleDeviceRoster] when the roster is
     * empty — a proxy that has not yet synced its device list must still run its
     * own routines rather than standing down forever.
     */
    fun realmRoster(
        executionContext: ExecutionContext,
        thisDeviceId: () -> String?,
    ): () -> List<RosterDevice> = {
        RoutineRosterSource(executionContext).roster()
            .takeIf { it.isNotEmpty() }
            ?: singleDeviceRoster(thisDeviceId)()
    }

    /**
     * The bootstrap roster: **this device only**.
     *
     * ⚠ RE-D2's claim is trivially "me" under this roster. Since RE-3 it is only
     * the **fallback** for a proxy whose device list has not synced yet — the
     * real roster is [realmRoster]. Kept because standing down forever is worse
     * than acting alone: a realm of one is the common case at first launch.
     */
    fun singleDeviceRoster(thisDeviceId: () -> String?): () -> List<RosterDevice> = {
        thisDeviceId()?.let {
            listOf(RosterDevice(deviceId = it, tier = CapabilityTier.RESIDENT))
        }.orEmpty()
    }
}
