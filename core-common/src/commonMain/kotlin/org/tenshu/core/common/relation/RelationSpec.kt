package org.tenshu.core.common.relation

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId

/**
 * Where a relation's backing array lives — the first of the two things that
 * genuinely differ between the ~30 hand-written relation maintainers
 * (`core-relation-system.md` §9.2). Everything else (the element's key, the
 * type match, the remove-then-add bookkeeping) is derivable and is derived by
 * [RelationMaintainer].
 */
sealed interface RelationScope {

    /**
     * A realm-wide array keyed by relation id — [DataId.RelationDataId]. The
     * shape 26 of the 30 surveyed maintainers use, and all 8 migrated in R1.
     */
    data class Service(val relationId: String) : RelationScope

    /**
     * A per-entity **field** array — [DataId.EntityFieldDataId]. `Folder.FILES`
     * is the archetype: the array hangs off the *parent* entity, so the spec
     * must say how to find that parent from the element.
     *
     * ⚠ [parentOf] is invoked on **both** observer paths, and the `Data` it
     * receives differs between them:
     *  - `onDataSaved` passes the saved element itself;
     *  - `onDataDeleted` passes the deleted element's own [DataId] (a [DataId]
     *    *is* a `Data`), because a delete carries no row.
     *
     * That asymmetry is not in §9.2 — the doc specifies a single
     * `(ExecutionContext, Data)` lambda without saying what the delete path
     * feeds it. Deriving the parent from the element's id is what
     * `ParentFilesRelationMaintainer` already does on delete (it reads the path
     * out of the `StringDataId`), so an implementation that handles both shapes
     * is exactly as expressive as the hand-written code it replaces. Returning
     * `null` skips the relation for that element.
     */
    data class EntityField(
        val fieldRef: String,
        val parentOf: (ExecutionContext, Data) -> DataId.EntityDataId?,
    ) : RelationScope

    /**
     * A per-entity **property** array — [DataId.EntityPropertyDataId]
     * (`File.FileHistory`). Same [parentOf] contract as [EntityField].
     */
    data class EntityProperty(
        val propertyId: String,
        val parentOf: (ExecutionContext, Data) -> DataId.EntityDataId?,
    ) : RelationScope
}

/**
 * One relation the generic [RelationMaintainer] maintains: "elements of
 * [elementTypeId] belong in the array named by [scope]".
 *
 * ⚠ **For a [RelationScope.Service] this is a derived shape, not a declaration
 * surface.** `ModuleConfiguration.service(...)` walks the service schema's
 * `relationSchemas` and derives a spec for every relation whose
 * `serviceDataRelation(id, type, maintained = true)` opted in (§9.2.1). The DSL
 * a module author writes describes *what the relation is*; this is the internal
 * shape maintenance is expressed in.
 *
 * The **entity-scoped** scopes are constructed directly, by the module that owns
 * the element type, through
 * `ModuleConfiguration.relationMembership(spec)` — because no schema carries
 * either the array key or the parent derivation for them (R2; the DSL verb's
 * KDoc has the reasoning).
 *
 * **Everything here is derived** — which is why the schema is enough to produce
 * it:
 *  - [elementTypeId] from `Type.elementSchema()` on the declared
 *    `TList.typeOf(X.Schema)`;
 *  - [scope] from the relation id;
 *  - [isEntity] from `elementSchema.isSubtypeOf(Entity.ID)` — the *same*
 *    derivation the chain reader in `ReferenceResolver.resolveServiceData` uses,
 *    so writer and reader agree by construction rather than by two hand-written
 *    constants happening to match;
 *  - **the element's key**, at maintenance time, from
 *    [ExecutionContext.getDataId] — which resolves the registered
 *    `DataIdProvider` trait *and* the identity-root type (`findIdentityRoot`), so
 *    a subtype's row keys under its root exactly as the store writes it. Every
 *    hand-rolled `keyOf`/`compositeKeyOf`/`shareIdOf`/`uuidOf` helper is
 *    redundant with it.
 *
 * Type matching is `schema.isSubtypeOf(elementTypeId)`, uniformly.
 *
 * [RelationScope.EntityField] / [RelationScope.EntityProperty] are not service
 * relations, so `ServiceSchema.relationSchemas` cannot carry them (§9.2.1);
 * R2 gave them their own declaration verb,
 * `ModuleConfiguration.relationMembership(spec)`, used by `Folder.FILES`,
 * `File.FileHistory` and `SharedFile`'s children tree.
 */
data class RelationSpec(
    val elementTypeId: String,
    val scope: RelationScope,
    val isEntity: Boolean = false,
)
