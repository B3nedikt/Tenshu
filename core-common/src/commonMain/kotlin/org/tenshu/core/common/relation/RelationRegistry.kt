package org.tenshu.core.common.relation

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId

/**
 * The read-back side of `ModuleConfiguration.registerRelationMaintainer` — the
 * relation twin of `SchemaRegistry.serviceDataRelations`
 * (`core-relation-system.md` §9.2 / D1).
 *
 * Holds the `elementTypeId → specs` index built once at construction, so a save
 * is one indexed lookup instead of a fan-out to N observers each re-running its
 * own type check.
 *
 * It also owns [referenceFor], so a reader and a writer of the same array can no
 * longer disagree about `isEntity` or `elementSchemaId` — the duplication §9.2
 * flags across `ErrorEventRecorderImpl`, `RealmKeyring`,
 * `GetRealmMembersImplementation` and friends. R2 folded those reader sites onto
 * it via [relationReference] (D4).
 */
class RelationRegistry(val specs: List<RelationSpec>) {

    /**
     * Exact-match index. Subtype resolution is the caller's job
     * ([RelationMaintainer] memoises it per concrete type) — a subtype's id is
     * not knowable at registration time.
     */
    private val byElementType: Map<String, List<RelationSpec>> = specs.groupBy { it.elementTypeId }

    /** Specs declared *directly* for [elementTypeId], ignoring subtyping. */
    fun declaredFor(elementTypeId: String): List<RelationSpec> =
        byElementType[elementTypeId].orEmpty()

    private val byServiceRelationId: Map<String, RelationSpec> = specs
        .mapNotNull { spec -> (spec.scope as? RelationScope.Service)?.let { it.relationId to spec } }
        .toMap()

    /** The [RelationScope.Service] spec declared for [relationId], if any. */
    fun serviceRelationSpec(relationId: String): RelationSpec? = byServiceRelationId[relationId]

    /**
     * The [ArrayDatabaseReference] [spec]'s members live in. [parent] is
     * required by — and only by — the entity-scoped variants; `null` there
     * yields `null` (the element has no addressable array, so it is skipped).
     */
    fun referenceFor(
        spec: RelationSpec,
        context: TContext,
        parent: DataId.EntityDataId? = null,
    ): ArrayDatabaseReference? {
        val arrayId: DataId.ReferencedDataId = when (val scope = spec.scope) {
            is RelationScope.Service -> DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = scope.relationId,
            )

            is RelationScope.EntityField -> DataId.EntityFieldDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = parent ?: return null,
                fieldRef = scope.fieldRef,
            )

            is RelationScope.EntityProperty -> DataId.EntityPropertyDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = parent ?: return null,
                propertyId = scope.propertyId,
            )
        }
        return ArrayDatabaseReference(
            arrayId = arrayId,
            isEntity = spec.isEntity,
            elementSchemaId = spec.elementTypeId,
        )
    }

    companion object {

        /**
         * Every spec registered across the module graph. Mirrors the
         * `(this as? SchemaRegistry)?.schemaMigrations()` precedent in
         * `SchemaVersioning.kt`: the specs live on the module configurations,
         * and [SchemaRegistry] is the only thing that holds those.
         */
        fun from(schemaProvider: SchemaProvider): RelationRegistry =
            RelationRegistry((schemaProvider as? SchemaRegistry)?.relationSpecs().orEmpty())
    }
}
