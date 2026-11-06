package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.UnknownParameter
import org.tenshu.core.toTString

data class UnknownParameterDto(
    private val fieldValues: List<Data?>
) : UnknownParameter, CompositeDto(fieldValues) {

    constructor(
        name: String,
        operationId: String
    ) : this(
        fieldValues = listOf(name.toTString(), operationId.toTString())
    )
}
