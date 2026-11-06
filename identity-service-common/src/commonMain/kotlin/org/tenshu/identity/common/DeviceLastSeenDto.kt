package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.number.TInteger
import org.tenshu.identity.DeviceLastSeen

class DeviceLastSeenDto(
    fieldValues: List<Data?>
) : DeviceLastSeen, CompositeDto(fieldValues) {

    override val deviceId: TString get() = getField(DeviceLastSeen.Fields.DEVICE_ID)
    override val lastSeenMs: TInteger get() = getField(DeviceLastSeen.Fields.LAST_SEEN_MS)
}
