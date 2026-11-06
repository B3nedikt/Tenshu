package app.tenshu.fit.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readOnce
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice
import kotlinx.coroutines.flow.first

/**
 * The proxy-backed [AutoDetectPreferences] — realm row for the want, per-device row for whether
 * this device has introduced the feature (`fit-automatic-activity-detection.md` D1).
 *
 * [resolveDeviceId] returns this device's sync id, or `null` while sync identity is still coming
 * up. Same resolver shape Calendar uses (`client.syncService.getSyncStatus()…deviceId`), passed in
 * rather than reached for so this module stays testable without a sync service.
 *
 * ⚠⚠ **A null id defers the prompt; it never substitutes a key.** Writing a per-device row under
 * a borrowed id silently changes some *other* device's answer — the `CalendarDeviceIdentity`
 * incident. Calendar could not defer (it had a visibility toggle to render *now*) and so needed a
 * retrying, latching identity class. A once-per-lifetime prompt can simply wait for the next
 * launch, which is why this is a lambda and eight lines rather than a third copy of that class.
 */
class TenshuAutoDetectPreferences(
    private val client: TenshuClient,
    private val resolveDeviceId: suspend () -> String?,
) : AutoDetectPreferences {

    // ⚠ `read()` emits nothing on a realm that never held the row, so a fresh install would
    // render no toggle state at all without the `onStart` seed.
    override fun enabled(): Flow<Boolean> =
        client.preferenceService.read(FitPreferences.AutoDetectActivities)
            .onStart { emit(FitPreferences.AutoDetectActivities.default) }

    // ⚠ `readOnce`, not `enabled().first()` — see the interface KDoc. The reactive read is
    // seeded with the default, so its head is the default and never the stored value.
    override suspend fun enabledOnce(): Boolean =
        client.preferenceService.readOnce(FitPreferences.AutoDetectActivities)

    override suspend fun setEnabled(value: Boolean) {
        client.preferenceService.set(FitPreferences.AutoDetectActivities, value)
    }

    /**
     * ⚠ Returns `true` — "consider it answered" — when there is no device id yet. That reads
     * backwards until you see what the alternative does: prompting during the identity race means
     * the answer cannot be written, so the prompt returns on the next launch, and the next. The
     * once-only promise is worth more than asking at the earliest possible moment.
     */
    override suspend fun promptAnswered(): Boolean {
        val deviceId = resolveDeviceId() ?: return true
        // ⚠⚠ `readOnceForDevice`, not the seeded reactive read. With `first()` this returned the
        // default (`false`) forever, so the prompt would have re-appeared on every launch —
        // breaking the one thing D1 promises. Same defect as `enabledOnce`, one row over.
        return client.preferenceService
            .readOnceForDevice(FitPreferences.AutoDetectPromptAnswered, deviceId)
    }

    override suspend fun markPromptAnswered() {
        // Refuse rather than substitute — see the class KDoc. Nothing to log loudly: the prompt
        // was never shown in this state, so there is no user-visible action being dropped.
        val deviceId = resolveDeviceId() ?: return
        client.preferenceService.setForDevice(
            pref = FitPreferences.AutoDetectPromptAnswered,
            value = true,
            deviceId = deviceId,
        )
    }
}
