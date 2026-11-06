package org.tenshu.preference

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Per-device setting row — one per `(deviceId, preferenceId)` pair. All
 * devices in the realm see every device's rows.
 *
 * The schema carries a derived `compositeKey = "$deviceId/$preferenceId"`
 * field, set by [Companion.create], so the existing single-field
 * `stringDataId(...)` `DataIdProvider` machinery can produce a stable
 * composite key. The public surface ([deviceId] + inherited [preferenceId])
 * is unchanged — `compositeKey` is an implementation detail the SDK chain
 * matches against when looking up a specific row.
 *
 * Per `core-preference-service.md` §3.9, these rows survive device
 * replacement / recovery via the regular sync layer: the old device's rows
 * stay until the user explicitly removes the device from the realm (at
 * which point the device-management tombstone propagates and clears them).
 */
interface DeviceSetting : Setting {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Field.DEVICE_ID)

    val compositeKey: TString
        get() = getField(Field.COMPOSITE_KEY)

    companion object {
        const val ID = "tenshu/preference/deviceSetting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Setting.Schema,
            traits = listOf(
                DeviceSettingDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager)
            ),
            fields = {
                string(Setting.Field.PREFERENCE_ID)
                genericData(Setting.Field.VALUE)
                string(Field.DEVICE_ID)
                string(Field.COMPOSITE_KEY)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            preferenceId: String,
            value: Data,
        ): DeviceSetting = context.create(
            type = ID,
            fields = listOf(preferenceId, value, deviceId, compositeKey(deviceId, preferenceId)),
        )

        /**
         * Build the derived `compositeKey` from its components. Exposed so the
         * SDK chain can construct the same key when searching for a specific
         * `(deviceId, preferenceId)` pair without first materialising the
         * row.
         */
        fun compositeKey(deviceId: String, preferenceId: String): String =
            "$deviceId/$preferenceId"
    }

    object Field {
        const val DEVICE_ID = "$ID/deviceId"
        const val COMPOSITE_KEY = "$ID/compositeKey"
    }
}

val Entity<out DeviceSetting>.deviceId: TString
    get() = getFieldValue(DeviceSetting.Field.DEVICE_ID)

interface DeviceSettingDataIdProvider : CompositeDataIdProvider<DeviceSetting> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/preference/deviceSetting/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = DeviceSetting.Field.COMPOSITE_KEY
        )
    }
}
