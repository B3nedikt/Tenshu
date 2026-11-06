package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.MissingParameter
import org.tenshu.core.toTString

data class MissingParameterDto(
    private val fieldValues: List<Data?>
) : MissingParameter, CompositeDto(fieldValues) {

    constructor(
        name: String
    ) : this(
        fieldValues = listOf(name.toTString())
    )
}
