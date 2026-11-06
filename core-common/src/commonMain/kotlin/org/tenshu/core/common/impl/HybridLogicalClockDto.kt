package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.HybridLogicalClock

class HybridLogicalClockDto(
    fieldValues: List<Data?>
) : HybridLogicalClock, CompositeDto(fieldValues) {

    override val physicalTime: TInteger get() = getField(HybridLogicalClock.Fields.PHYSICAL_TIME)
    override val logicalCounter: TInteger get() = getField(HybridLogicalClock.Fields.LOGICAL_COUNTER)
    override val deviceId: TString get() = getField(HybridLogicalClock.Fields.DEVICE_ID)
}
