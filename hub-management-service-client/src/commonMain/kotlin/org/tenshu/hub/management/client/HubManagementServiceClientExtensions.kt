package org.tenshu.hub.management.client

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
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.issueHubCommand

val TenshuClient.hubManagementService: HubManagementService
    get() = getService(HubManagementService.ID)

/**
 * All known hub-authored [HubStatus] snapshots — a **local read** of the
 * synced rows (§7.4-shaped ergonomics over the H4 synced-state inversion;
 * see [HubStatus]'s KDoc). Empty when no hub has ever published into this
 * realm, or the read fails.
 */
suspend fun HubManagementService.getHubStatuses(): List<HubStatus> =
    hubStatuses.getAll().getOrNull()?.toList().orEmpty()

/**
 * The freshest hub-authored snapshot (by [HubStatus.freshAsOf]), or `null`
 * when no hub has published one. The snapshot is "as of the hub's last
 * write" — check [HubStatus.freshAsOf] before treating it as current.
 */
suspend fun HubManagementService.getHubStatus(): HubStatus? =
    getHubStatuses().maxByOrNull { it.freshAsOf.get() }

/**
 * Reactive twin of [getHubStatuses]: re-emits whenever a hub status row
 * changes locally or arrives via sync.
 */
fun HubManagementService.hubStatusesFlow(): Flow<List<HubStatus>> =
    hubStatuses.flow().map { result ->
        (result as? TResult.Success)?.value?.toList().orEmpty()
    }

/**
 * Requests one immediate keep-everything backup pass on the hub
 * [targetDeviceId] (H3 `runBackupPassNow`). [commandId] defaults to a fresh
 * UUID — keep it if you want to correlate the outcome
 * ([HubStatus.lastCommandId] / [HubStatus.lastBackupPassAtEpochMs]).
 */
suspend fun HubManagementService.runBackupNow(
    targetDeviceId: String,
    commandId: String = generateId(),
): TResult<Data?> = issueHubCommand(
    commandId = commandId,
    targetDeviceId = targetDeviceId,
    kind = HubCommand.KIND_RUN_BACKUP_NOW,
    mode = "",
)

/**
 * Requests a restart of the hub [targetDeviceId]. [mode] =
 * [HubCommand.MODE_PROXY] (restart the proxy process; systemd re-spawns it)
 * or [HubCommand.MODE_SYSTEM] (reboot the node — v1-unimplemented, the hub
 * reports [HubStatus.OUTCOME_UNSUPPORTED]). [commandId] as in [runBackupNow].
 */
suspend fun HubManagementService.restartHub(
    targetDeviceId: String,
    mode: String = HubCommand.MODE_PROXY,
    commandId: String = generateId(),
): TResult<Data?> = issueHubCommand(
    commandId = commandId,
    targetDeviceId = targetDeviceId,
    kind = HubCommand.KIND_RESTART,
    mode = mode,
)
