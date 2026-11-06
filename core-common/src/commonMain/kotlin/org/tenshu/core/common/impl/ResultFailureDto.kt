package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TError
import org.tenshu.core.TResult

data class ResultFailureDto<T : Data>(
    private val fieldValues: List<Data?>
) : TResult.Failure<T>, CompositeDto(fieldValues) {

    constructor(
        error: TError
    ) : this(
        fieldValues = listOf(error)
    )
}
