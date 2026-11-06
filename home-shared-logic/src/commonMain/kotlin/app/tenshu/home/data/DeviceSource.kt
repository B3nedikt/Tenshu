package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow

/**
 * Plain-model projection of one hub-bridged device — the `HomeDevice`
 * inventory row joined with its `DeviceState` snapshot
 * (`home-device-service.md` HD-D5) — everything Home's Devices tab needs,
 * decoupled from the schema types so the repository (and its tests) never
 * touch a `TContext` (the [HubStatusSource]/[HubStatusInfo] pattern).
 *
 * Nullable state fields mean "this device does not report that dimension"
 * (the client source maps the schema's sentinel encodings back to null).
 * A device whose state row has not synced yet carries state defaults with
 * [stateFresh] = false.
 */
data class SyncedDeviceInfo(
    val deviceId: String,
    /** `HomeDevice.KIND_*` value; unknown kinds render as a generic sensor row. */
    val kind: String,
    /** Which bridge published this row — the `permitJoin` command's target (HD-2). */
    val bridgeId: String = "",
    /** `HomeDevice.CAP_*` ids this device advertises. */
    val capabilities: List<String> = emptyList(),
    val name: String,
    /** Room string (`""` = unassigned) — D5, not a Room entity. */
    val room: String,
    /** True once a `DeviceState` row for this device exists. */
    val stateFresh: Boolean = false,
    val online: Boolean = false,
    val powerOn: Boolean? = null,
    val levelPercent: Int? = null,
    val temperatureDeciC: Int? = null,
    val humidityPercent: Int? = null,
    val batteryPercent: Int? = null,
    /**
     * `DeviceCommand.commandId` of the last command the hub consumed for this
     * device (`""` = none) — the correlation handle a caller uses to learn what
     * became of *its* command (HD-1 put outcomes on the state row rather than
     * inventing an ack type).
     */
    val lastCommandId: String = "",
    /**
     * `DeviceState.OUTCOME_*` for [lastCommandId]; `""` = none. The honest
     * answer to "did my toggle work?" — value equality on [powerOn] cannot
     * distinguish "the hub did it" from "someone else did it".
     */
    val lastCommandOutcome: String = "",
)

/**
 * Home's read seam over the home-device-service client surface — sits beside
 * [HubStatusSource] (the storage tile's seam, same pattern). The real
 * implementation ([ClientDeviceSource]) joins the reactive `homeDevices` +
 * `deviceStates` reads; a fake feeds repository tests.
 *
 * Contract: emits the currently-known bridged devices and re-emits on
 * change. An unreachable proxy, a failed read, or a realm with no bridged
 * devices all surface as an **empty list** — never as an error — so
 * consumers fall back to the mock household exactly as if no hub existed
 * (the simulated bridge's devices are REAL rows; the mock is Home's
 * client-side fallback — the two never mix).
 */
interface DeviceSource {
    fun devices(): Flow<List<SyncedDeviceInfo>>
}

/**
 * Home's write seam for HD-2's "Add device": opening a bridge's pairing
 * window (`home-device-service.md` HD-D7 — explicit and time-bounded, never
 * persistent). Separate from [DeviceSource] because it is the one *command*
 * the Devices tab issues, and because a Home build with no Tenshu client
 * simply has no implementation of it (the mock household cannot pair
 * anything, and must not pretend it can).
 */
interface DevicePairingSource {

    /**
     * Ask [bridgeId] to admit new devices for [seconds]. Returns false when
     * the command could not be issued at all; **true only means the command
     * row was written** — the hub clamps the window and the radio decides
     * what joins, so the honest UI signal is devices actually appearing.
     */
    suspend fun openPairingWindow(bridgeId: String, seconds: Int): Boolean
}

/**
 * Home's actuation seam — turning a bridged device on/off and setting its
 * level. Sibling of [DevicePairingSource] and separate from [DeviceSource] for
 * the same reason: a Home build with no Tenshu client has no implementation,
 * and the mock household must not pretend to be switchable.
 *
 * ⚠ **Absolute, never relative.** The service also offers a `toggle` verb, and
 * this seam deliberately does not expose it: a switch expresses the state the
 * user *wants*, while a toggle expresses a flip relative to a reading that may
 * already be stale by the time the hub consumes the row — the two disagree
 * exactly when it matters (a state row landing between the tap and the
 * command). `toggle` remains right for a physical button; it is wrong for a UI
 * control that shows the current state.
 *
 * Both calls take the [commandId] from the caller so it can correlate the
 * outcome that arrives on the device's state row
 * ([SyncedDeviceInfo.lastCommandId]) — which is also what makes the whole path
 * idempotent (the hub's consumed journal is keyed by that id).
 *
 * Returns false when the command could not be *issued*; **true only means the
 * command row was written.** Whether the bulb moved is the state row's answer,
 * never this one's.
 */
interface DeviceControlSource {

    suspend fun setPower(deviceId: String, on: Boolean, commandId: String): Boolean

    /** Sets the dim/position level (0–100; the service clamps). */
    suspend fun setLevel(deviceId: String, percent: Int, commandId: String): Boolean
}
