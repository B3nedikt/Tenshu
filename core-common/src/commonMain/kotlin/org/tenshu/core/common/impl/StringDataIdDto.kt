package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId

class StringDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.StringDataId {

    constructor(
        type: String,
        keyValue: String
    ) : this(
        fieldValues = listOf(type.toTString(), keyValue.toTString())
    )

    override fun toString(): String {
        return "StringDataId(type=${type.get()}, keyValue=${keyValue.get()})"
    }
}
