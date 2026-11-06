package org.tenshu.home.device.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.HomeDevice

internal class HomeDeviceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HomeDevice {

    override val schema: CompositeSchema
        get() = HomeDevice.Schema
}
