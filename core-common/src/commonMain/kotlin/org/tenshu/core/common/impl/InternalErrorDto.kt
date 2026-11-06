package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.InternalError
import org.tenshu.core.toTString

data class InternalErrorDto(
    private val fieldValues: List<Data?>
) : InternalError, CompositeDto(fieldValues) {

    constructor(
        internalDetail: String?
    ) : this(
        fieldValues = listOf(internalDetail?.toTString())
    )
}
