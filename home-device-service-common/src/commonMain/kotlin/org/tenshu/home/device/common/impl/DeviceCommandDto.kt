package org.tenshu.home.device.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.DeviceCommand

internal class DeviceCommandDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DeviceCommand {

    override val schema: CompositeSchema
        get() = DeviceCommand.Schema
}
