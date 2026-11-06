package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.number.TInteger
import org.tenshu.identity.Device

class DeviceDto(
    fieldValues: List<Data?>
) : Device, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(Device.Fields.DEVICE_ID)
    override val name: TString get() = getField(Device.Fields.NAME)
    override val publicKey: TByteArray get() = getField(Device.Fields.PUBLIC_KEY)
    override val isAuthorized: TBoolean get() = getField(Device.Fields.IS_AUTHORIZED)
    override val admittedAtEpoch: TInteger get() = getField(Device.Fields.ADMITTED_AT_EPOCH)
    override val admittedByDeviceId: TString? get() = getOptionalField(Device.Fields.ADMITTED_BY_DEVICE_ID)
    override val admissionPublicKey: TByteArray? get() = getOptionalField(Device.Fields.ADMISSION_PUBLIC_KEY)
}
