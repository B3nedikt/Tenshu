package org.tenshu.core.relation

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.reference

/**
 * Service-binding relation keyed by [Type] — covers both single-entity and
 * collection cases on a single shape:
 *
 * - Single-entity case: `type = Folder.Schema.toType()`, `dataId = StringDataIdDto(...)`
 *   resolves to one entity via `database.getData(dataId)`.
 * - Collection case: `type = TList.typeOf(Notification.Schema)`, `dataId = null`
 *   resolves to an `ArrayDatabaseReference` keyed by relation id.
 *
 * Resolution: `ExecutionContext.resolveServiceData(service, ref)` (where `ref`
 * is the registered `EntityReference` / `EntityListReference`).
 *
 * This is the explicit-endpoint kind of [Relation]: [from] is the binding's
 * service side, [to] its data side. (The property-selector kind of [Relation]
 * has no `from`/`to` — see [Relation].)
 */
interface ServiceDataRelation : Relation {

    override val schema: CompositeSchema
        get() = Schema

    val from: Data
        get() = getField(Fields.FROM)

    val to: Data
        get() = getField(Fields.TO)

    val type: Type
        get() = getField(Fields.TYPE)

    val dataId: DataId.EntityDataId?
        get() = getOptionalField(Fields.DATA_ID)

    companion object {
        const val ID = "tenshu/core/relation/serviceData"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Relation.Schema,
            fields = {
                composite(id = Fields.FROM)
                composite(id = Fields.TO)
                reference(Fields.TYPE, type = Type.Schema)
                composite(id = Fields.DATA_ID, type = DataId.Schema, isOptional = true)
            }
        )
    }

    object Fields {
        const val FROM = "${ID}/from"
        const val TO = "${ID}/to"
        const val TYPE = "${ID}/type"
        const val DATA_ID = "${ID}/dataId"
    }
}
