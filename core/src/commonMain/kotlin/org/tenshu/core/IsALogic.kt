package org.tenshu.core

import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.isSubtypeOf

/**
 * The evaluation half of [isA]: `true` when [receiver]'s own type is [typeId] or descends from it.
 *
 * ⚠⚠ **Where a row's type id sits depends on how the row was read**, and getting this wrong reads
 * every folder as not-a-folder. A plain [Composite] carries it as its `schema`; an [Entity]'s
 * `schema` is always `Entity.Schema` and the real id is `Entity.type`. An entity therefore needs
 * a [schemaProvider] to walk its parent chain — the proxy always has one; in-memory code that
 * tests an entity against a *supertype* without one gets a loud error rather than a quiet
 * `false`, because that is a wiring bug (`lowercaseLogic` takes the same stance).
 */
fun isALogic(receiver: Data, typeId: String, schemaProvider: SchemaProvider? = null): TBoolean {
    val ownTypeId = (receiver as? Entity<*>)?.type?.get() ?: receiver.schema.id.get()
    if (ownTypeId == typeId) return true.toTBoolean()

    val ownSchema: DataSchema? = if (receiver is Entity<*>) {
        (schemaProvider ?: error(
            "isA('$typeId') on an entity of type '$ownTypeId' needs a SchemaProvider to walk its " +
                "type hierarchy; none was given"
        )).getSchema(ownTypeId)
    } else {
        receiver.schema
    }
    return (ownSchema?.isSubtypeOf(typeId) ?: false).toTBoolean()
}
