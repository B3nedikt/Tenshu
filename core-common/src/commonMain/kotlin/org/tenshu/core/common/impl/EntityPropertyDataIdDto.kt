package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toTString
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema

/**
 * Wire DTO for [DataId.EntityPropertyDataId] — the per-entity property-relation array
 * key (e.g. `File("/a").FileHistory`). The property-id sibling of
 * [EntityFieldDataIdDto]; registered in `CoreCommonModule` so the array key round-trips
 * through the proxy `BinaryParser` like the other `ReferencedDataId`s (unregistered,
 * the key cannot cross the wire).
 */
class EntityPropertyDataIdDto(
    fieldValues: List<Data?>
) : DataIdDto(fieldValues), DataId.EntityPropertyDataId {

    override val schema: CompositeSchema
        get() = DataId.EntityPropertyDataId.Schema

    constructor(
        type: String,
        parentEntity: DataId.EntityDataId,
        propertyId: String
    ) : this(
        fieldValues = listOf(type.toTString(), parentEntity, propertyId.toTString())
    )

    override fun toString(): String {
        return "EntityPropertyDataId(type=${type.get()}, parent=${parentEntity}, property=${propertyId.get()})"
    }
}
