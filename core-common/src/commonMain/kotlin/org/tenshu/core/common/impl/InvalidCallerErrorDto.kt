package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.toTString

data class InvalidCallerErrorDto(
    private val fieldValues: List<Data?>
) : InvalidCallerError, CompositeDto(fieldValues) {

    constructor(
        message: String
    ) : this(
        fieldValues = listOf(message.toTString())
    )
}
