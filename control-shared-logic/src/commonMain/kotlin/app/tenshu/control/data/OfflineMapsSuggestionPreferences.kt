package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.ControlPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.setForDevice

/**
 * Whether the user dismissed Home's offline-maps suggestion on *this* device. Backed by
 * [ControlPreferences.OfflineMapsSuggestionDismissed]; mirrors [QuotaSoftLimitPreferences].
 */
interface OfflineMapsSuggestionPreferences {
    fun observeDismissed(): Flow<Boolean>
    suspend fun setDismissed()
}

class TenshuOfflineMapsSuggestionPreferences(
    private val client: TenshuClient,
    private val syncRepository: SyncRepository,
) : OfflineMapsSuggestionPreferences {

    private var cachedDeviceId: String? = null

    private suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val resolved = syncRepository.getDeviceId().takeIf { it.isNotBlank() }
        if (resolved != null) {
            cachedDeviceId = resolved
            return resolved
        }
        return LOCAL_FALLBACK_DEVICE_ID
    }

    override fun observeDismissed(): Flow<Boolean> = flow {
        emitAll(
            client.preferenceService.readForDevice(
                ControlPreferences.OfflineMapsSuggestionDismissed,
                deviceId(),
            ),
        )
    }

    override suspend fun setDismissed() {
        client.preferenceService.setForDevice(
            ControlPreferences.OfflineMapsSuggestionDismissed,
            true,
            deviceId(),
        )
    }

    private companion object {
        const val LOCAL_FALLBACK_DEVICE_ID = "local-device"
    }
}
