package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.entity.DataId

data class StreamElementRemovedDto(
    val fieldValues: List<Data?>
) : StreamElement.Removed<Data>, CompositeDto(fieldValues) {

    constructor(
        streamId: Long,
        removedId: DataId
    ) : this(
        fieldValues = listOf(streamId.toInteger(), removedId)
    )
}
