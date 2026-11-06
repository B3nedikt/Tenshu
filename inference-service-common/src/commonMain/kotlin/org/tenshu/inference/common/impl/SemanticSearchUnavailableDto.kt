package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.SemanticSearchUnavailable

internal class SemanticSearchUnavailableDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SemanticSearchUnavailable {

    override val schema: CompositeSchema get() = SemanticSearchUnavailable.Schema
}
