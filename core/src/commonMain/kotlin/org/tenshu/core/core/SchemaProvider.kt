package org.tenshu.core.core

import org.tenshu.core.Data
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.EntityRelationDescriptor
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.ReferencePropertyDescriptor
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.trait.Trait
import kotlin.reflect.KClass

interface SchemaProvider : TComponent {

    /**
     * Every registered [org.tenshu.core.schema.ServiceSchema], in registration
     * order — the walk the schema-version vector is assembled from
     * (`core-schema-evolution.md` §4.1.1: versions ride the schemas, the
     * dependency closure rides the module `include(...)` edges, so nothing is
     * declared separately that could drift). Default empty for providers that
     * hold no module graph.
     */
    fun serviceSchemas(): Collection<org.tenshu.core.schema.ServiceSchema> = emptyList()

    fun getReference(type: String): Reference

    fun getTypeId(type: ByteArray): String

    fun getSchema(type: String): DataSchema?

    fun getServiceRelation(from: List<String>): String?

    /**
     * Looks up a `ServiceDataRelation` by `(service, ref)`. The [ref] may be either
     * an `EntityReference` (single-entity case) or `EntityListReference` (collection
     * case) — both are subtypes of [Reference].
     */
    fun getServiceDataRelation(
        service: ServiceTypeReference,
        ref: Reference,
    ): org.tenshu.core.relation.ServiceDataRelation?

    fun getTrait(type: String, trait: String): Trait?

    fun getTrait(type: String, traitSchema: org.tenshu.core.trait.TraitSchema): Trait?

    fun getTypeId(kClass: KClass<Data>): String

    /**
     * Reference-property metadata for the property [propertyId], or `null` if it is an
     * ordinary stored/constant value property. When present, resolution dereferences a
     * foreign entity by key rather than reading a stored cell
     * (`docs/proposals/core-reference-property.md`). Defaulted to `null` so non-proxy
     * [SchemaProvider]s (test fakes, JS) need not implement it.
     */
    fun getReferenceProperty(
        propertyId: String,
    ): ReferencePropertyDescriptor? = null

    /**
     * Entity-relation metadata for the property [propertyId], or `null` if it is an
     * ordinary value/dynamic property. When present, resolution reads a per-entity
     * array keyed by the receiver's `DataId` (an `EntityPropertyDataId`) rather than
     * a stored cell (`docs/proposals/core-nested-projection.md` §3.3). Defaulted to
     * `null` so non-proxy [SchemaProvider]s (test fakes, JS) need not implement it.
     */
    fun getEntityRelation(
        propertyId: String,
    ): EntityRelationDescriptor? = null

    /**
     * Type ids of every data type that **declares** the
     * [RealmData][org.tenshu.core.trait.RealmData] marker trait (proposal
     * `docs/proposals/core-realm-data.md`). Keys off declared — not inherited — traits, so a
     * sub-type whose schema parents a realm-data type folds into its parent rather than
     * appearing as a separate entry (§2.4). Returned in registration order; presentation and
     * ordering are the consumer's concern (a `control-shared` presentation registry keyed by
     * these ids), since they are localisable / consumer-specific.
     *
     * Defaulted to an empty list so non-proxy [SchemaProvider]s (test fakes, bootstrap)
     * need not implement it; the concrete `SchemaRegistry` walks its registered schemas.
     */
    fun realmDataTypeIds(): List<String> = emptyList()

    /**
     * Every **collection-shaped** `ServiceDataRelation` whose array holds rows of
     * [typeId] — the reverse of [getServiceDataRelation], and the only way to reach
     * *all rows of a type*: `Database` has no type-wide enumeration, rows are only ever
     * reachable through the relation array that holds them. Used by `DeleteRealmData`
     * (`docs/proposals/control-data-reset.md` §4).
     *
     * Matching includes **sub-types**: an array of `TList<Folder>` is returned for
     * `typeId = File.ID`, mirroring how [realmDataTypeIds] folds a sub-type into the
     * parent that declares the marker (§2.4) — a reset of "Files" that skipped folders
     * would be a half-reset.
     *
     * ⚠ A type with **no** service-data relation returns an empty list, and that is a
     * truthful answer, not a failure: its rows live somewhere else (`File`'s live in the
     * parent-folder tree) and are the business of that type's own delete op.
     *
     * Defaulted to an empty list so non-proxy [SchemaProvider]s (test fakes, bootstrap)
     * need not implement it; the concrete `SchemaRegistry` walks its registered configs.
     */
    fun serviceDataArraysHolding(
        typeId: String,
    ): List<org.tenshu.core.relation.ServiceDataArrayBinding> = emptyList()

    /**
     * The value property [typeId] bound as fulfilling the value trait requirement
     * [requirementId], or `null` if none is bound. v1's use: resolving
     * `RealmData.CREATED_AT` to a type's creation-timestamp property so the Data section
     * sorts items newest-first generically (`docs/proposals/core-realm-data.md` §2.6 / §8).
     * Resolves through the type hierarchy, so a sub-type inherits its parent's binding.
     *
     * Defaulted to `null` so non-proxy [SchemaProvider]s (test fakes, bootstrap) need not
     * implement it; the concrete `SchemaRegistry` looks the binding up.
     */
    fun resolveValueRequirement(
        typeId: String,
        requirementId: String,
    ): Property.Value<*>? = null

    companion object {
        const val COMPONENT_ID = "core/SCHEMA_PROVIDER"
    }
}

fun TContext.schemaProvider(): SchemaProvider =
    components[SchemaProvider.COMPONENT_ID] as SchemaProvider
