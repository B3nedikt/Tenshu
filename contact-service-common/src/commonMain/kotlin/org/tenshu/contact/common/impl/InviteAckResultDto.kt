package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.InviteAckResult

internal class InviteAckResultDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), InviteAckResult {

    override val schema: CompositeSchema
        get() = InviteAckResult.Schema
}
