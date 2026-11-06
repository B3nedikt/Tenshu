package org.tenshu.hub.management.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.hub.management.HubCommand

internal class HubCommandDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HubCommand {

    override val schema: CompositeSchema
        get() = HubCommand.Schema
}
