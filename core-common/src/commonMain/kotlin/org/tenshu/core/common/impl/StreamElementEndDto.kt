package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.common.util.toInteger

data class StreamElementEndDto(
    val fieldValues: List<Data?>
) : StreamElement.End<Data>, CompositeDto(fieldValues) {

    constructor(
        streamId: Long,
        error: TError?
    ) : this(
        fieldValues = listOf(streamId.toInteger(), error)
    )
}