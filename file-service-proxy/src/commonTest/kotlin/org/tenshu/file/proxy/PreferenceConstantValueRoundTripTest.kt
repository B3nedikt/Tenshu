package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileCategory
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the storage-layer gap that blocked storing a `TConstant` in the
 * polymorphic `genericData` `Setting.value` slot: the write serialised the constant
 * as a `StringValue` of its schema id, but the read routed every non-`ValueSchema`
 * genericData slot through a `LongValue` cast — throwing
 * `StringValue cannot be cast to LongValue`. The `DbEntryMapper` now handles
 * `ConstantSchema` on read (both the genericData and the positional constant-field
 * paths), so a constant round-trips through the real `DeviceSetting.value` DB path.
 *
 * Uses the already-registered `FileCategory` constant — the exact scenario that
 * first surfaced the failure, now asserted to pass.
 */
class PreferenceConstantValueRoundTripTest {

    private fun proxy(): TestProxy =
        TestProxy(
            schemaRegistry = createFileTestRegistry().apply {
                registerConfig(preferenceServiceCommonModule)
            }
        ).apply { executionContext.initFileService() }

    @Test
    fun constantRoundTripsThroughDeviceSettingValue() {
        val proxy = proxy()
        val ctx = proxy.tContext

        proxy.executionContext.saveData(
            Entity.create<DeviceSetting>(
                context = ctx,
                composite = DeviceSetting.create(
                    context = ctx,
                    deviceId = "phone",
                    preferenceId = "probe.constant",
                    value = FileCategory.Video,
                ),
            )
        )

        val stored = proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = ctx,
                type = DeviceSetting.ID,
                keyValue = DeviceSetting.compositeKey("phone", "probe.constant"),
            )
        )

        val value: Data? = when (stored) {
            is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
            is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
            else -> null
        }

        assertEquals(FileCategory.Video, value, "constant round-trips through the DeviceSetting.value genericData slot")
    }
}
