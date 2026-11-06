package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TResult

data class ResultSuccessDto<T : Data>(
    private val fieldValues: List<Data?>
) : TResult.Success<T>, CompositeDto(fieldValues) {

    constructor(
        value: T?
    ) : this(
        fieldValues = listOf(value)
    )
}
