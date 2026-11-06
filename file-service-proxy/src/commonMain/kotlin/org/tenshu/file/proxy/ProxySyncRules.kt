package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.PathSyncOverrideSetAdapter
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.LongAdapter
import org.tenshu.preference.Setting

/**
 * Reads [deviceId]'s effective [SyncRuleSet] directly off the proxy DB — the
 * device-default policy + sparse overrides stored as the two `file.sync.*`
 * `DeviceSetting` rows. The in-proxy counterpart of the client-side
 * `FileSynchronisationSettings.fileSyncRuleSetFor`, used by the offline keeper
 * (`OfflineKeeperImplementation`). Mirrors `FileSyncSettingsRelationMaintainer`'s
 * read path.
 *
 * NB: this does **not** fold the legacy per-file `drive.offline.files` pins
 * (they live in the `:drive-shared-logic` app module, out of reach here) — the
 * keeper reconciles folder-granular rules only. A per-file pin still downloads
 * immediately when set; the keeper covers content arriving later under a
 * `KEEP_OFFLINE` folder rule.
 */
internal fun ExecutionContext.readFileSyncRuleSet(deviceId: String): SyncRuleSet {
    // defaultPolicy is stored as the SyncPolicy constant itself (no codec); the
    // overrides value is a PathSyncOverrideSet composite the adapter unwraps.
    val defaultPolicy = readDeviceSettingValue(deviceId, FileSyncPreferences.DefaultPolicy.id)
        ?.let { it as? SyncPolicy }
        ?: FileSyncPreferences.DefaultPolicy.default
    val overrides = readDeviceSettingValue(deviceId, FileSyncPreferences.Overrides.id)
        ?.let { PathSyncOverrideSetAdapter.fromData(it) }
        ?: FileSyncPreferences.Overrides.default
    return SyncRuleSet(deviceDefault = defaultPolicy, overrides = overrides)
}

/**
 * A `Long`-valued device setting, or [fallback] when unset — the local-cache
 * ceiling and free-disk floor (`retention-quota-model.md` Face B).
 *
 * ⚠ Decoded through [LongAdapter] itself rather than by re-casting to the
 * underlying `Data` type here — the two would otherwise be free to drift, and the
 * failure would be a setting that silently reads as its default. A value stored by
 * some other adapter decodes to `null` and falls back, which for the ceiling means
 * "unlimited": visibly inert rather than a coerced zero that would disable it.
 */
internal fun ExecutionContext.readLongDeviceSetting(
    deviceId: String,
    preferenceId: String,
    fallback: Long,
): Long = LongAdapter.fromData(readDeviceSettingValue(deviceId, preferenceId)) ?: fallback

/** The stored polymorphic `value` of the `(deviceId, preferenceId)` row, or null. */
private fun ExecutionContext.readDeviceSettingValue(deviceId: String, preferenceId: String): Data? {
    val dataId = DataId.StringDataId.create(
        context = context,
        type = DeviceSetting.ID,
        keyValue = DeviceSetting.compositeKey(deviceId, preferenceId),
    )
    val stored = database.getData(dataId) ?: return null
    return when (stored) {
        is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
        is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
        else -> null
    }
}
