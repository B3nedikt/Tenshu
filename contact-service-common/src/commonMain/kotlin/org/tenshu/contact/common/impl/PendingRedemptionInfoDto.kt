package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.PendingRedemptionInfo

internal class PendingRedemptionInfoDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PendingRedemptionInfo {

    override val schema: CompositeSchema
        get() = PendingRedemptionInfo.Schema
}
