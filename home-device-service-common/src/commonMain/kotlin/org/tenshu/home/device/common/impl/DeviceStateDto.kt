package org.tenshu.home.device.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.DeviceState

internal class DeviceStateDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DeviceState {

    override val schema: CompositeSchema
        get() = DeviceState.Schema
}
