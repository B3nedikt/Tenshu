package app.tenshu.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The hub's pluggable radio seam (`home-device-service.md` HD-D3): radio
 * stacks are native territory, the JVM proxy orchestrates — a bridge exposes
 * *what devices exist*, *what state they're in*, and *execute this verb*,
 * and the schema/sync layer above ([DeviceBridgeHost]) never knows which
 * radio a device came from.
 *
 * Bridge #0 is [SimulatedDeviceBridge] (HD-D4, no hardware); bridge #1 is
 * the HD-2 `Zigbee2MqttBridge` (MQTT client against a local mosquitto +
 * zigbee2mqtt sidecar pair). Nothing in this seam presumes Zigbee.
 *
 * Contract notes for implementors:
 *  - [devices]/[states] are **state flows** (current value replayed to every
 *    subscriber) so host start order can never miss an emission; the host
 *    change-gates, so re-emitting an unchanged snapshot is free.
 *  - Device ids are already bridge-scoped (`"<bridgeId>:<local-id>"`) —
 *    the id IS the realm-wide row key, mint it stable.
 *  - [execute] returns when the bridge has *applied* (simulated) or
 *    *accepted* (real radio) the command; [BridgeCommandResult.newState]
 *    carries the resulting state when known synchronously (the simulated
 *    case), `null` when the radio confirms asynchronously via [states]
 *    (the zigbee2mqtt case).
 */
interface DeviceBridge {

    /** Stable bridge id — the [org.tenshu.home.device.HomeDevice.bridgeId] value. */
    val bridgeId: String

    /** Current device inventory; re-emits on join/leave/re-announce. */
    fun devices(): Flow<List<BridgeDevice>>

    /** Current per-device state snapshots; re-emits whenever any device's state moves. */
    fun states(): Flow<List<BridgeDeviceState>>

    /** Execute [verb] (+[argValue]) on [deviceId]. Never throws for a bad verb — returns UNSUPPORTED. */
    suspend fun execute(deviceId: String, verb: String, argValue: String): BridgeCommandResult

    /**
     * Open the radio's admission window for [seconds] (0 closes it) — the
     * HD-D7 admission gate. Implementors MUST clamp to
     * [org.tenshu.home.device.DeviceCommand.PERMIT_JOIN_MAX_SECONDS] and MUST
     * NOT keep the window open by default: joining is an explicit,
     * time-bounded act. Reached from the ordinary command path (the host
     * routes `permitJoin` addressed to [bridgeId] here), so it inherits the
     * consumed journal and the 2-minute staleness window for free — a stale
     * "open the network" command replaying later is exactly what must not
     * happen.
     */
    suspend fun setPermitJoin(seconds: Int): BridgeCommandResult

    /** Start radio/timer work in [scope]. Idempotent. */
    fun start(scope: CoroutineScope)

    /** Stop radio/timer work. Idempotent. */
    fun stop()
}

/**
 * A bridge-reported device identity — the hub-authored half of a
 * [org.tenshu.home.device.HomeDevice] row (the user-editable `name`/`room`
 * live on the synced row, not here; [defaultName] only seeds the row on
 * first join).
 */
data class BridgeDevice(
    /** Bridge-scoped stable id, `"<bridgeId>:<local-id>"` — the row key. */
    val deviceId: String,
    /** One of the `HomeDevice.KIND_*` constants. */
    val kind: String,
    val manufacturer: String = "",
    val model: String = "",
    /** `HomeDevice.CAP_*` capability ids. */
    val capabilityIds: List<String> = emptyList(),
    /** Seeds `HomeDevice.name` on first join; never overwrites a user rename. */
    val defaultName: String,
    /**
     * Per-bridge identity extras for the row's `genericData` slot — a compact
     * JSON object (zigbee2mqtt puts the IEEE address, the friendly name it
     * addresses the device by, the power source, …). `""` = nothing to say.
     * ⚠ Air-sourced content: sanitised + length-bounded by the bridge.
     */
    val extras: String = "",
)

/**
 * A bridge-reported state snapshot — nullable fields mean "this device does
 * not report that dimension"; [DeviceBridgeHost] maps them onto the
 * [org.tenshu.home.device.DeviceState] sentinel encodings at the schema
 * boundary.
 */
data class BridgeDeviceState(
    val deviceId: String,
    val online: Boolean = true,
    val powerOn: Boolean? = null,
    val levelPercent: Int? = null,
    val temperatureDeciC: Int? = null,
    val humidityPercent: Int? = null,
    val batteryPercent: Int? = null,
    val colorTempMireds: Int? = null,
    val powerDrawDeciW: Int? = null,
    /**
     * Attributes the typed fields do not model (zigbee2mqtt's `linkquality`,
     * `voltage`, `update`, vendor-specific keys, …) as a compact JSON object
     * for the row's `genericData` slot — never silently dropped, never
     * allowed to grow unbounded. `""` = nothing extra.
     *
     * ⚠ Deliberately NOT part of the publish gate's change comparison
     * ([DeviceStatePublishGate.quantise]): `linkquality` moves on every
     * report, and letting it trigger writes would defeat the flood brake.
     * Extras ride along with whatever publish the typed fields earn.
     */
    val extras: String = "",
)

/**
 * Outcome of one [DeviceBridge.execute]: [outcome] is a
 * [org.tenshu.home.device.DeviceState] `OUTCOME_*` value; [newState] is the
 * resulting state when the bridge knows it synchronously (see interface KDoc).
 */
data class BridgeCommandResult(
    val outcome: String,
    val newState: BridgeDeviceState? = null,
)
