package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.InvalidParameter
import org.tenshu.core.toTString

data class InvalidParameterDto(
    private val fieldValues: List<Data?>
) : InvalidParameter, CompositeDto(fieldValues) {

    constructor(
        name: String,
        reason: String
    ) : this(
        fieldValues = listOf(name.toTString(), reason.toTString())
    )
}
