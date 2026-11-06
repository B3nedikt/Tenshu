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
 * Reads/writes the "user dismissed the recovery affordance until X" timestamp
 * for *this* device. Backed by
 * [ControlPreferences.RecoveryAffordanceDismissedUntilMs] (DEVICE-scoped
 * `Long`, epoch ms) — see `docs/proposals/onboarding-low-friction.md` §7.2
 * Level 0.
 *
 * Mirrors `TenshuOfflinePreferences` in `:drive-shared`: DEVICE-scoped writes
 * need an explicit `deviceId`, and the SDK has no `currentDeviceId` resolver,
 * so the implementation resolves it once via [SyncRepository.getDeviceId]
 * and caches it.
 */
interface RecoveryAffordancePreferences {
    /** Emits this device's dismiss-until timestamp; `0L` = never dismissed. */
    fun observeDismissedUntilMs(): Flow<Long>

    /** Sets the dismiss-until timestamp on this device. */
    suspend fun setDismissedUntilMs(epochMs: Long)

    /** Whether the user has confirmed they saved their recovery phrase (§5b.12.1). */
    fun observePhraseSaved(): Flow<Boolean>

    suspend fun setPhraseSaved(saved: Boolean)
}

class TenshuRecoveryAffordancePreferences(
    private val client: TenshuClient,
    private val syncRepository: SyncRepository,
) : RecoveryAffordancePreferences {

    private var cachedDeviceId: String? = null

    /**
     * The key the dismiss timestamp is scoped to. Prefers the real synced device
     * id, but falls back to [LOCAL_FALLBACK_DEVICE_ID] when it isn't available
     * yet (e.g. `getSyncStatus()` failing on a never-synced single-device
     * install — exactly the state in which this banner shows). The earlier
     * `null`-return made both read and write silent no-ops, so the Dismiss
     * button did nothing. The fallback is never cached, so the real id is
     * adopted as soon as sync surfaces it.
     */
    private suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val resolved = syncRepository.getDeviceId().takeIf { it.isNotBlank() }
        if (resolved != null) {
            cachedDeviceId = resolved
            return resolved
        }
        return LOCAL_FALLBACK_DEVICE_ID
    }

    override fun observeDismissedUntilMs(): Flow<Long> = flow {
        emitAll(
            client.preferenceService.readForDevice(
                ControlPreferences.RecoveryAffordanceDismissedUntilMs,
                deviceId(),
            ),
        )
    }

    override suspend fun setDismissedUntilMs(epochMs: Long) {
        client.preferenceService.setForDevice(
            ControlPreferences.RecoveryAffordanceDismissedUntilMs,
            epochMs,
            deviceId(),
        )
    }

    override fun observePhraseSaved(): Flow<Boolean> = flow {
        emitAll(
            client.preferenceService.readForDevice(
                ControlPreferences.RecoveryPhraseSaved,
                deviceId(),
            ),
        )
    }

    override suspend fun setPhraseSaved(saved: Boolean) {
        client.preferenceService.setForDevice(
            ControlPreferences.RecoveryPhraseSaved,
            saved,
            deviceId(),
        )
    }

    private companion object {
        const val LOCAL_FALLBACK_DEVICE_ID = "local-device"
    }
}
