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
 * Reads/writes the one-time "quota soft-limit banner dismissed" flag for *this*
 * device. Backed by [ControlPreferences.QuotaSoftLimitBannerDismissed]
 * (DEVICE-scoped `Boolean`) — the free-tier 80% nudge is shown at most once and
 * its dismissal (or a *See plans* tap) is permanent
 * (`docs/proposals/control-subscription-ux.md` §2.3 / `retention-quota-model.md` P1b).
 *
 * Mirrors [RecoveryAffordancePreferences]: DEVICE-scoped writes need an explicit
 * `deviceId`, and the SDK has no `currentDeviceId` resolver, so the implementation
 * resolves it once via [SyncRepository.getDeviceId] and caches it, falling back to
 * a local id when sync hasn't surfaced the real one yet.
 */
interface QuotaSoftLimitPreferences {
    /** Emits `true` once the banner has been dismissed / actioned on this device. */
    fun observeDismissed(): Flow<Boolean>

    /** Permanently marks the banner dismissed on this device. */
    suspend fun setDismissed()
}

class TenshuQuotaSoftLimitPreferences(
    private val client: TenshuClient,
    private val syncRepository: SyncRepository,
) : QuotaSoftLimitPreferences {

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
                ControlPreferences.QuotaSoftLimitBannerDismissed,
                deviceId(),
            ),
        )
    }

    override suspend fun setDismissed() {
        client.preferenceService.setForDevice(
            ControlPreferences.QuotaSoftLimitBannerDismissed,
            true,
            deviceId(),
        )
    }

    private companion object {
        const val LOCAL_FALLBACK_DEVICE_ID = "local-device"
    }
}
