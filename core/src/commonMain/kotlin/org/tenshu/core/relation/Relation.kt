package org.tenshu.core.relation

import org.tenshu.core.Composite
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * Marker supertype for relation composites — values that express how data
 * relates to other data. Two concrete shapes today:
 *
 *  - [ServiceDataRelation] — an explicit-endpoint binding (a service `from` → a
 *    data target / collection `to`); a one-to-one (or one-to-collection) edge.
 *  - [PropertyRelation] / [StaticPropertyRelation] / [DynamicPropertyRelation] — a projection relating
 *    each entity in scope to its value / constant (the property selectors); a
 *    many-entities-to-many-values shape.
 *
 * `Relation` itself carries **no fields**: not every relation has two endpoints.
 * `from` / `to` are specific to [ServiceDataRelation]; a projection has a single
 * reference (+ optional params), not a `from`/`to` pair. This mirrors the
 * schema-descriptor side, where `RelationSchema` is already the shared supertype
 * of the property- and service-relation schemas. See
 * `docs/adr/0013-property-authoring-unification.md`.
 */
interface Relation : Composite {

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/relation"

        val Schema: CompositeSchema = composite(id = ID)
    }
}
