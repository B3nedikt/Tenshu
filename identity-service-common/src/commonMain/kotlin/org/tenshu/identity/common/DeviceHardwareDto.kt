package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.DeviceHardware

class DeviceHardwareDto(
    fieldValues: List<Data?>
) : DeviceHardware, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(DeviceHardware.Fields.DEVICE_ID)
    override val hardwareName: TString get() = getField(DeviceHardware.Fields.HARDWARE_NAME)
}
