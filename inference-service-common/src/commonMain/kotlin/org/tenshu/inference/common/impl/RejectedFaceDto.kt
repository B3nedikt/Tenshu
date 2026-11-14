package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.RejectedFace

internal class RejectedFaceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RejectedFace {

    override val schema: CompositeSchema get() = RejectedFace.Schema
}
