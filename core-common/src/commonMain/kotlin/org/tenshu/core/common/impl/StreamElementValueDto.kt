package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.common.util.toInteger

data class StreamElementValueDto(
    val fieldValues: List<Data?>
) : StreamElement.Value<Data>, CompositeDto(fieldValues) {

    constructor(
        streamId: Long,
        payload: Data
    ) : this(
        fieldValues = listOf(streamId.toInteger(), payload)
    )
}