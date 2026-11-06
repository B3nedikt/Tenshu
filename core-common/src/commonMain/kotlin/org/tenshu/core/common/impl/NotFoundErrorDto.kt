package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.toTString

data class NotFoundErrorDto(
    private val fieldValues: List<Data?>
) : NotFoundError, CompositeDto(fieldValues) {

    constructor(
        message: String
    ) : this(
        fieldValues = listOf(message.toTString())
    )

    override fun toString(): String = fieldValues.firstOrNull()?.toString() ?: "Not Found Error"
}
