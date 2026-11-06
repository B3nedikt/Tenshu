package app.tenshu.drive.data

import app.tenshu.ingest.DeviceIdentity
import app.tenshu.drive.preferences.DrivePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.setForDevice

/**
 * Reads/writes Drive's per-device "keep offline" file list
 * ([DrivePreferences.OfflineFiles]) through the preference service.
 *
 * [DrivePreferences.OfflineFiles] is DEVICE-scoped, so reads/writes are keyed
 * by the device's sync identity. The client only learns that id from the
 * proxy, so the implementation resolves it via [DeviceIdentity] (mirroring
 * Control's `TenshuSyncRepository.getDeviceId`) and caches it.
 */
interface OfflinePreferences {
    /** Emits this device's offline file list, re-emitting on every change. */
    fun observe(): Flow<List<String>>

    /** Replaces this device's offline file list. */
    suspend fun setOffline(paths: List<String>)
}

class TenshuOfflinePreferences(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : OfflinePreferences {

    // Awaits identity rather than completing empty when it is not up yet — see [DeviceIdentity].
    override fun observe(): Flow<List<String>> = flow {
        emitAll(
            client.preferenceService.readForDevice(DrivePreferences.OfflineFiles, identity.await())
        )
    }

    override suspend fun setOffline(paths: List<String>) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(DrivePreferences.OfflineFiles, paths, id)
    }
}
