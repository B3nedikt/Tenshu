package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.MeteredNetworkRefusedError
import org.tenshu.core.schema.CompositeSchema

internal class MeteredNetworkRefusedErrorDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MeteredNetworkRefusedError {
    override val schema: CompositeSchema get() = MeteredNetworkRefusedError.Schema
}
