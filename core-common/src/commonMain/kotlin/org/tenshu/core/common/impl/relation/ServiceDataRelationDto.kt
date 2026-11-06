package org.tenshu.core.common.impl.relation

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.entity.DataId
import org.tenshu.core.relation.ServiceDataRelation
import org.tenshu.core.schema.Type

/**
 * Public factory mirroring `Type.create(...)` / `Field.create(...)` — call sites
 * outside `:core-common` use this to construct a `ServiceDataRelation` value
 * without depending on the internal [ServiceDataRelationDto] class.
 */
fun ServiceDataRelation.Companion.create(
    from: String,
    to: String,
    type: Type,
    dataId: DataId.EntityDataId? = null,
): ServiceDataRelation = ServiceDataRelationDto(from, to, type, dataId)

internal class ServiceDataRelationDto(
    private val fieldValues: List<Data?>,
) : ServiceDataRelation, CompositeDto(fieldValues) {

    constructor(
        from: String,
        to: String,
        type: Type,
        dataId: DataId.EntityDataId? = null,
    ) : this(
        listOf(
            ServiceTypeReferenceDto(from),
            EntityReferenceDto(to),
            type,
            dataId,
        )
    )

    override fun toString(): String {
        return "ServiceDataRelation(" +
                "from=${fieldValues.getOrNull(0)}, " +
                "to=${fieldValues.getOrNull(1)}, " +
                "type=${type}, " +
                "dataId=$dataId" +
                ")"
    }
}
