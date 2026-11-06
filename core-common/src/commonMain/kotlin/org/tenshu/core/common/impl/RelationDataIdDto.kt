package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema

class RelationDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.RelationDataId {

    override val schema: CompositeSchema
        get() = DataId.RelationDataId.Schema

    constructor(
        type: String,
        relationId: String
    ) : this(
        fieldValues = listOf(type.toTString(), relationId.toTString())
    )
}
