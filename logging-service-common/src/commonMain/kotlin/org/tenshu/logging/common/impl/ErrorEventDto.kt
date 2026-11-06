package org.tenshu.logging.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.logging.ErrorEvent

internal class ErrorEventDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ErrorEvent {

    override val schema: CompositeSchema
        get() = ErrorEvent.Schema
}
