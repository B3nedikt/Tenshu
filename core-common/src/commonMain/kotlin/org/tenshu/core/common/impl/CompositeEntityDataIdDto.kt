package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId

class CompositeEntityDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.CompositeEntityDataId {

    constructor(
        type: String,
        keyValues: List<DataId.ValueDataId>
    ) : this(
        fieldValues = listOf(type.toTString(), keyValues.toTList())
    )
}