package org.tenshu.core.schema

import org.tenshu.core.collection.list.TList

interface ServiceSchema : DataSchema {
    val relationSchemas: TList<RelationSchema>
        get() = getField(Fields.RELATIONS)

    /**
     * This service's schema version (`core-schema-evolution.md` §4) — bumped in the
     * same file as any wire-visible schema change to the service, so the Δ and the
     * bump land on the same screenful.
     *
     * Code-side schema metadata, **never serialized** (the `@defer`
     * `deferredByDefault` precedent: positional composites make added fields
     * peer-breaking, so schema metadata stays off the wire). It reaches the wire
     * only through the frozen `VersionVector` envelope — the announce op (E1) and
     * the block stamp. A `ServiceSchema` materialized from bytes reports the
     * default; only the DSL-declared instance carries the real value.
     */
    val version: Int
        get() = 1

    companion object {
        const val ID = "tenshu/core/data/schema/service"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = DataSchema.Schema,
            fields = {
                list(Fields.RELATIONS, of = RelationSchema.Schema)
            }
        )
    }

    object Fields {
        const val RELATIONS = "${ID}/relations"
    }
}
