package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Setting

/*
 * Proxy-side reads and writes of one device's `(deviceId, preferenceId)` setting row.
 *
 * ⚠ Deliberately the store, not the preference *client*: every caller is already on the proxy side of
 * the IPC boundary, so going out through `PreferenceService` would be a round trip from the proxy back
 * into itself. (These lived beside ST-3b's candidate list until CR-2 B6 removed it.)
 */

/** Replace the `(deviceId, preferenceId)` row's value. */
internal suspend fun ExecutionContext.writeDeviceSettingValue(
    deviceId: String,
    preferenceId: String,
    value: Data,
) {
    saveData(
        DeviceSetting.create(
            context = context,
            deviceId = deviceId,
            preferenceId = preferenceId,
            value = value,
        )
    )
}

/**
 * The stored polymorphic `value` of the `(deviceId, preferenceId)` row, or null.
 *
 * ⚠ A near-twin of `ProxySyncRules`' private helper of the same shape, which stays separate: that
 * one is typed to the sync-rule reader's own adapters, and sharing would mean a module for two
 * eight-line reads.
 */
internal fun ExecutionContext.readDeviceSettingData(deviceId: String, preferenceId: String): Data? {
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
