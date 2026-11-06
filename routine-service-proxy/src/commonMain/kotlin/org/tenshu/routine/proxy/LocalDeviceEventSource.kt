package org.tenshu.routine.proxy

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One observed device state transition, as the bridge saw it — **before** the
 * `DeviceState` row is published.
 *
 * @param lastCommandId the `DeviceCommand.commandId` that caused this change, or
 *   `""` when the change came from elsewhere (a wall switch, a sensor, a timer
 *   in the device itself). ⭐ This is what makes RE-D9's self-trigger
 *   suppression free — see [RoutineEngine].
 */
class ObservedStateChange(
    val deviceId: String,
    val fields: Map<String, String>,
    val freshAsOfEpochMs: Long,
    val lastCommandId: String = "",
)

/**
 * The device-event fast path (`routines-engine.md` RE-D1 / RE-D11, RE-2).
 *
 * ⚠⚠ **Bound only in a process that hosts a `DeviceBridge`.** A remote observer
 * sees a device change as a `DeviceState` row at the HD-D2 3–5 s poll cadence,
 * which is useless for motion→light; the bridge host sees it in milliseconds.
 * That asymmetry is exactly why RE-D1 makes eligibility fall out of
 * observability rather than policy — and why a device-event routine simply does
 * not arm on a phone.
 *
 * ⭐ Being a seam rather than a hub-only code path is what keeps the engine
 * itself hub-agnostic: `RoutineEngine` collects this when it is present and is
 * otherwise unchanged.
 */
interface LocalDeviceEventSource {
    fun events(): Flow<ObservedStateChange>
}

/**
 * The implementation a bridge host emits into.
 *
 * ⚠⚠ **`replay` is non-zero on purpose.** HD-2 found that a `replay = 0` hot
 * flow silently drops what lands before the collector attaches — there it cost
 * the whole retained device inventory, here it costs a missed trigger, which is
 * worse because nothing looks broken. A short tail means an event that arrives
 * during engine start-up is still seen.
 *
 * ⚠ `DROP_OLDEST` rather than suspending: a flapping sensor must never be able
 * to block the bridge host's collection loop. Losing the oldest of a flood is
 * the right failure — the RE-D9 throttle is what actually bounds a runaway.
 */
class MutableLocalDeviceEventSource(
    replay: Int = REPLAY_TAIL,
) : LocalDeviceEventSource {

    private val flow = MutableSharedFlow<ObservedStateChange>(
        replay = replay,
        extraBufferCapacity = EXTRA_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun events(): Flow<ObservedStateChange> = flow.asSharedFlow()

    /** Never suspends (see the class KDoc); returns false when the event was dropped. */
    fun emit(change: ObservedStateChange): Boolean = flow.tryEmit(change)

    private companion object {
        const val REPLAY_TAIL = 8
        const val EXTRA_BUFFER = 64
    }
}
