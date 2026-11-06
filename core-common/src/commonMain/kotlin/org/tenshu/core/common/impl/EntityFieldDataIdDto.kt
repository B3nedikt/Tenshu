package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema

class EntityFieldDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.EntityFieldDataId {

    override val schema: CompositeSchema
        get() = DataId.EntityFieldDataId.Schema

    constructor(
        type: String,
        parentEntity: DataId.EntityDataId,
        fieldRef: String
    ) : this(
        fieldValues = listOf(type.toTString(), parentEntity, fieldRef.toTString())
    )

    override fun toString(): String {
        return "EntityFieldDataId(type=${type.get()}, parent=${parentEntity}, field=${fieldRef.get()})"
    }
}
