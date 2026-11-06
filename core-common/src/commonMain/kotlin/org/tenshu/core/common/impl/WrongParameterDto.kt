package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.WrongParameter
import org.tenshu.core.toTString

data class WrongParameterDto(
    private val fieldValues: List<Data?>
) : WrongParameter, CompositeDto(fieldValues) {

    constructor(
        name: String,
        actualTypeId: String,
        expectedTypeId: String
    ) : this(
        fieldValues = listOf(
            name.toTString(),
            actualTypeId.toTString(),
            expectedTypeId.toTString()
        )
    )
}
