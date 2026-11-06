package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Placeholder that [SelectImplementation] writes into a projected entity
 * when a `relations(...)` selector targets a relation whose schema declares
 * `Syncable(SyncStrategy.OnDemand)` AND the local resolution chain returned
 * `null` (i.e. the bytes are not yet present on this device).
 *
 * The placeholder records what needs to be fetched ([entityId],
 * [propertyId], [params]) so the **terminal** operation (`Maybe.get()`,
 * `.flow()`) can dispatch [OnDemandResolution.resolve], wait for the bytes
 * to land in the local database, and then re-resolve the property to
 * replace this marker with the real value.
 *
 * `Select` stays synchronous — it only *describes* what should be
 * loaded. The actual async I/O happens at the terminal, mirroring the
 * existing deferred-reference pattern used by [ArrayDatabaseReference]
 * (defer materialisation until `getArrayData` is called).
 *
 * Modelled as a real Composite (not an in-memory-only data class) so
 * the placeholder roundtrips through IPC as inert wire-safe data on the
 * rare paths that don't run [materialisePendingOnDemandProperties] — and
 * so the parser doesn't crash with an unknown type ID before the
 * terminal has a chance to do its work. The fields are declared on
 * [Schema] so `BinarySerializer`/`BinaryParser` preserve
 * [entityId]/[propertyId]/[params] across the wire.
 *
 * Callers that bypass `Select` won't see this placeholder. Callers that
 * do `.select { relations(...) }` followed by `.get()` see it
 * materialised on their behalf by [MaybeGetImplementation]; callers
 * that do the same chain followed by `.flow()` see it materialised by
 * the per-element pass in [ExecutionContext.setDatabaseListener]. Both
 * routes share [materialisePendingOnDemandProperties]. Chains that
 * filter by an OnDemand relation between Select and a terminal (rare;
 * none in production today) will see the unresolved placeholder — not
 * solved here, documented as a known limitation.
 */
interface PendingOnDemandProperty : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val entityId: DataId
        get() = getField(Fields.ENTITY_ID)

    val propertyId: TString
        get() = getField(Fields.PROPERTY_ID)

    val params: TList<Data>?
        get() = getOptionalField(Fields.PARAMS)

    companion object {
        const val ID = "tenshu/core/proxy/pendingOnDemandProperty"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                composite(Fields.ENTITY_ID, type = DataId.Schema)
                string(Fields.PROPERTY_ID)
                list(Fields.PARAMS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?
        ): PendingOnDemandProperty = context.create(
            type = ID,
            fields = listOf(
                entityId,
                propertyId.toTString(context),
                params
            )
        )
    }

    object Fields {
        const val ENTITY_ID = "$ID/entityId"
        const val PROPERTY_ID = "$ID/propertyId"
        const val PARAMS = "$ID/params"
    }
}
