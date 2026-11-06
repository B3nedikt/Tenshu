package org.tenshu.identity

import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull

/**
 * Resolve the human-friendly name of the device with [deviceId] (e.g. "Pixel 8")
 * from the synced device registry ([getDevices]), or `null` when it can't be
 * resolved — the registry isn't reachable yet, the row hasn't synced, or the name
 * is blank. Callers fall back to the raw `deviceId`.
 *
 * Used for the `/Backup/<device>/…` destination root (Synced Folders, §4.5): both
 * Drive's in-process drain and Control's desktop `FolderWatcher` resolve the same
 * way so the realm layout is consistent across the device's own surfaces.
 */
suspend fun IdentityService.deviceName(deviceId: String): String? =
    getDevices().getOrNull()?.toList()
        ?.firstOrNull { it.deviceId.get() == deviceId }
        ?.name?.get()
        ?.takeIf { it.isNotBlank() }
