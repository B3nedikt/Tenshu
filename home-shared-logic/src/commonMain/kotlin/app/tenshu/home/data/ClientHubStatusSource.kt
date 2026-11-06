package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.common.TenshuClient
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.client.hubManagementService
import org.tenshu.hub.management.client.hubStatusesFlow

/**
 * The real [HubStatusSource]: Home's Tenshu client reading the hub-authored
 * `HubStatus` rows through `:hub-management-service-client` (a local reactive
 * read of the synced rows — hub proposal §13 H4). Every failure mode — no
 * proxy connection, an older Control without the hub-management wiring, a
 * realm with no hub — degrades to an empty emission, which is exactly the
 * repository's fall-back-to-mock signal.
 */
class ClientHubStatusSource(
    private val client: TenshuClient,
) : HubStatusSource {

    override fun statuses(): Flow<List<HubStatusInfo>> = flow {
        emitAll(
            client.hubManagementService.hubStatusesFlow().map { rows ->
                rows.map { it.toInfo() }
            },
        )
    }.catch { emptyOnUnreachable("hub-status", it) }

    private fun HubStatus.toInfo(): HubStatusInfo = HubStatusInfo(
        deviceId = deviceId.get(),
        name = name.get(),
        diskFreeBytes = diskFreeBytes.get(),
        diskTotalBytes = diskTotalBytes.get(),
        replicaFileCount = replicaFileCount.get(),
        replicaByteCount = replicaByteCount.get(),
        lastBackupPassAtEpochMs = lastBackupPassAtEpochMs.get(),
        freshAsOfEpochMs = freshAsOf.get(),
    )
}
