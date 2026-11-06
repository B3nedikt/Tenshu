package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.UnsupportedOperationError
import org.tenshu.core.toTString

data class UnsupportedOperationErrorDto(
    private val fieldValues: List<Data?>
) : UnsupportedOperationError, CompositeDto(fieldValues) {

    constructor(
        message: String,
        operationId: String,
        typeTag: String,
    ) : this(
        fieldValues = listOf(
            message.toTString(),
            operationId.toTString(),
            typeTag.toTString(),
        )
    )

    override fun toString(): String =
        fieldValues.firstOrNull()?.toString() ?: "Unsupported operation"
}
