package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.toTString

data class GenericErrorDto(
    private val fieldValues: List<Data?>
) : GenericError, CompositeDto(fieldValues) {

    constructor(
        message: String
    ) : this(
        fieldValues = listOf(message.toTString())
    )

    override fun toString(): String = fieldValues.firstOrNull()?.toString() ?: "Unknown Error"
}
