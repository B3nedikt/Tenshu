package org.tenshu.home.device.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.core.id

/**
 * Initialises the home-device service's proxy-side observers.
 *
 * [HomeDeviceService.Relations.HOME_DEVICES] /
 * [HomeDeviceService.Relations.DEVICE_STATES] /
 * [HomeDeviceService.Relations.DEVICE_COMMANDS] are derived views of every
 * locally-known [HomeDevice] / [DeviceState] / [DeviceCommand] — whether saved
 * by a local operation (`IssueDeviceCommandImplementation`, the hub's
 * `DeviceBridgeHost` publisher) **or** arriving via the sync-merge path.
 * Relation membership is not a synced primitive; the receiver derives it.
 *
 * All three are **declared on [HomeDeviceService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * they were the local `HomeDeviceRelationMaintainer`.
 */
fun ExecutionContext.initHomeDeviceService() {
    installRelationMaintainer()
}
