package org.tenshu.timeline.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.timeline.SubjectRef

internal class SubjectRefDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SubjectRef {

    override val schema: CompositeSchema get() = SubjectRef.Schema
}
