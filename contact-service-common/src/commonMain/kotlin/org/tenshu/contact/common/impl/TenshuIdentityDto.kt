package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.TenshuIdentity

internal class TenshuIdentityDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), TenshuIdentity {

    override val schema: CompositeSchema
        get() = TenshuIdentity.Schema
}
