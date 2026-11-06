package org.tenshu.preference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.preference.DeviceSetting

internal class DeviceSettingDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DeviceSetting {

    override val schema: CompositeSchema
        get() = DeviceSetting.Schema
}
