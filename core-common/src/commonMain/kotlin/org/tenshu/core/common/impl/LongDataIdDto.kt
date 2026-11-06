package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId

class LongDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.LongDataId {

    constructor(
        type: String,
        keyValue: Long
    ) : this(
        fieldValues = listOf(type.toTString(), keyValue.toInteger())
    )
}