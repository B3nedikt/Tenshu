package org.tenshu.hub.management.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.hub.management.HubStatus

internal class HubStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HubStatus {

    override val schema: CompositeSchema
        get() = HubStatus.Schema
}
