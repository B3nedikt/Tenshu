package org.tenshu.home.device.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.generateId
import org.tenshu.core.getOrNull
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.issueDeviceCommand
import org.tenshu.home.device.updateHomeDevice

val TenshuClient.homeDeviceService: HomeDeviceService
    get() = getService(HomeDeviceService.ID)

/**
 * All known hub-authored [HomeDevice] inventory rows — a **local read** of
 * the synced rows (the H4 synced-state inversion). Empty when no hub has
 * ever published a device into this realm, or the read fails.
 */
suspend fun HomeDeviceService.getHomeDevices(): List<HomeDevice> =
    homeDevices.getAll().getOrNull()?.toList().orEmpty()

/**
 * Reactive twin of [getHomeDevices]: re-emits whenever a device row changes
 * locally or arrives via sync.
 */
fun HomeDeviceService.homeDevicesFlow(): Flow<List<HomeDevice>> =
    homeDevices.flow().map { result ->
        (result as? TResult.Success)?.value?.toList().orEmpty()
    }

/** All known hub-authored [DeviceState] snapshots — a local read, like [getHomeDevices]. */
suspend fun HomeDeviceService.getDeviceStates(): List<DeviceState> =
    deviceStates.getAll().getOrNull()?.toList().orEmpty()

/**
 * Reactive twin of [getDeviceStates]: re-emits whenever a state row changes
 * locally or arrives via sync.
 */
fun HomeDeviceService.deviceStatesFlow(): Flow<List<DeviceState>> =
    deviceStates.flow().map { result ->
        (result as? TResult.Success)?.value?.toList().orEmpty()
    }

/** The freshest known state for [deviceId], or `null` when none synced yet. */
suspend fun HomeDeviceService.getDeviceState(deviceId: String): DeviceState? =
    getDeviceStates().firstOrNull { it.deviceId.get() == deviceId }

/**
 * Turns [targetDeviceId] on or off ([DeviceCommand.VERB_SET_POWER]).
 * [commandId] defaults to a fresh UUID — keep it if you want to correlate
 * the outcome ([DeviceState.lastCommandId] / [DeviceState.lastCommandOutcome]).
 */
suspend fun HomeDeviceService.setDevicePower(
    targetDeviceId: String,
    on: Boolean,
    commandId: String = generateId(),
): TResult<Data?> = issueDeviceCommand(
    commandId = commandId,
    targetDeviceId = targetDeviceId,
    verb = DeviceCommand.VERB_SET_POWER,
    argValue = if (on) DeviceCommand.ARG_ON else DeviceCommand.ARG_OFF,
)

/** Flips [targetDeviceId]'s power state ([DeviceCommand.VERB_TOGGLE]). */
suspend fun HomeDeviceService.toggleDevice(
    targetDeviceId: String,
    commandId: String = generateId(),
): TResult<Data?> = issueDeviceCommand(
    commandId = commandId,
    targetDeviceId = targetDeviceId,
    verb = DeviceCommand.VERB_TOGGLE,
)

/**
 * Sets [targetDeviceId]'s dim/position level to [percent] (0–100, clamped;
 * [DeviceCommand.VERB_SET_LEVEL] — implies power-on at a level > 0).
 */
suspend fun HomeDeviceService.setDeviceLevel(
    targetDeviceId: String,
    percent: Int,
    commandId: String = generateId(),
): TResult<Data?> = issueDeviceCommand(
    commandId = commandId,
    targetDeviceId = targetDeviceId,
    verb = DeviceCommand.VERB_SET_LEVEL,
    argValue = percent.coerceIn(0, 100).toString(),
)

/**
 * Opens [bridgeId]'s pairing window for [seconds] (HD-2 "Add device"):
 * a [DeviceCommand.VERB_PERMIT_JOIN] addressed to the **bridge row**
 * ([HomeDevice.CAP_PERMIT_JOIN] advertises which rows accept it).
 *
 * The window is clamped hub-side to [DeviceCommand.PERMIT_JOIN_MAX_SECONDS]
 * whatever is asked for here, and the ordinary command machinery applies: a
 * stale (> 2 min) request never opens the radio. Pass `seconds = 0` to close
 * a window early.
 */
suspend fun HomeDeviceService.openPairingWindow(
    bridgeId: String,
    seconds: Int = DeviceCommand.PERMIT_JOIN_DEFAULT_SECONDS,
    commandId: String = generateId(),
): TResult<Data?> = issueDeviceCommand(
    commandId = commandId,
    targetDeviceId = bridgeId,
    verb = DeviceCommand.VERB_PERMIT_JOIN,
    argValue = seconds.coerceIn(0, DeviceCommand.PERMIT_JOIN_MAX_SECONDS).toString(),
)

/** Renames the [HomeDevice] row [deviceId] to [name] (user-editable field, HD-D1). */
suspend fun HomeDeviceService.renameDevice(
    deviceId: String,
    name: String,
): TResult<Data?> = updateHomeDevice(
    deviceId = deviceId,
    name = name,
    setName = true,
)

/** Re-rooms [deviceId] to [room] (`""` = unassigned; user-editable field, HD-D1/D5). */
suspend fun HomeDeviceService.setDeviceRoom(
    deviceId: String,
    room: String,
): TResult<Data?> = updateHomeDevice(
    deviceId = deviceId,
    room = room,
    setRoom = true,
)
