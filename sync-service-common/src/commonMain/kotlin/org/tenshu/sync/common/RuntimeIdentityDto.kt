package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.RuntimeIdentity

internal class RuntimeIdentityDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RuntimeIdentity {

    override val schema: CompositeSchema
        get() = RuntimeIdentity.Schema
}
