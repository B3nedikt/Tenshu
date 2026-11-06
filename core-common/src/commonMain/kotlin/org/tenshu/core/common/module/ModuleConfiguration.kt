package org.tenshu.core.common.module

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.core.TContext
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.EntityRelationDescriptor
import org.tenshu.core.relation.ReferencePropertyDescriptor
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema
import kotlin.reflect.KClass

interface ModuleConfiguration {

    /**
     * Other modules whose contributions this module assumes are present in the
     * registry. Populated via [include]; resolved depth-first at
     * `SchemaRegistry.registerConfig` time, deduplicated by referential
     * identity. Replaces the eager-merge semantics that `include()` used to
     * carry on its own.
     *
     * Order matters: dependency-declaration order at the include site is
     * preserved as registration order at registerConfig time, so when a
     * transitive prerequisite (A→B→C) is walked, C is registered before B
     * and B before A.
     */
    val prerequisites: List<ModuleConfiguration>

    val typeMapping: Map<ByteArray, String>

    val classToTypeMapping: MutableMap<KClass<out Data>, String>

    val referenceMapping: Map<String, Reference>

    val schemaMapping: Map<String, DataSchema>

    val relations: Map<List<String>, String>

    /**
     * Service-binding relations registered as `ServiceDataRelation` values. Keyed by
     * `(ServiceTypeReference, Reference)` because the target reference may be either
     * an `EntityReference` (single entity by id) or an `EntityListReference`
     * (collection by item type) — the [Reference] parent type covers both.
     */
    val serviceDataRelations: Map<Pair<ServiceTypeReference, Reference>, org.tenshu.core.relation.ServiceDataRelation>

    val traitFactories: Map<Pair<String, String>, (TContext, TraitSchema) -> Trait>

    val valueFactories: Map<String, (value: Any) -> Data>

    /**
     * Reference value properties — property id → descriptor (key field on the receiver +
     * foreign target type). A *proxy-side* resolution detail (never serialised); the wire
     * selector stays the uniform `StaticPropertyRelation`. See
     * `docs/proposals/core-reference-property.md`.
     */
    val referenceProperties: Map<String, ReferencePropertyDescriptor>

    /**
     * Entity relations — property id → descriptor (the array's element type). A
     * *proxy-side* resolution detail (never serialised); the wire selector stays the
     * uniform `StaticPropertyRelation`. The one-to-many counterpart of
     * [referenceProperties]. See `docs/proposals/core-nested-projection.md` §3.3.
     */
    val entityRelations: Map<String, EntityRelationDescriptor>

    /**
     * Bindings that fulfil a trait's [ValueTraitRequirement][org.tenshu.core.trait.ValueTraitRequirement]:
     * `(typeId, requirementId)` → the receiver's value property that satisfies it. v1's only
     * use is `RealmData.CREATED_AT` (a type binds its creation-timestamp property so the Data
     * section can sort items newest-first generically — `docs/proposals/core-realm-data.md`
     * §2.6 / §8). A *proxy-side* resolution detail (a Kotlin handle, never serialised), like
     * [referenceProperties] / [entityRelations].
     */
    val valueRequirementBindings: Map<Pair<String, String>, org.tenshu.core.relation.Property.Value<*>>

    val constantFactories: Map<String, () -> Data>

    val compositeFactories: Map<String, (fields: List<Data?>) -> Data>

    val requestObjectFactories: Map<String, (RequestChain, TContext, Type) -> Data>

    val operations: Map<String, Map<String, (RequestChain, TContext) -> Operation>>

    /**
     * The module's registered [SchemaMigration] steps (`core-schema-evolution.md`
     * §8.4) — one per service version bump, carrying the frozen per-type
     * snapshots. Code-side artifacts, never serialised. Registration rides the
     * same module hook as the service's version declaration so the compiler
     * enforces the "schema Δ + `SchemaMigration`, same PR" pairing; the
     * readable floor is DERIVED from these
     * ([org.tenshu.core.common.schema.migration.SchemaMigrationRegistry.floorFor]).
     */
    val schemaMigrations: List<SchemaMigration>

    /**
     * Relations this module declares as generically maintained
     * (`core-relation-system.md` §9.2.1) — read back through
     * [org.tenshu.core.common.relation.RelationRegistry] and served by the one
     * generic [org.tenshu.core.common.relation.RelationMaintainer].
     *
     * ⚠ **Service relations are derived, not declared.** There is deliberately no
     * `register…` verb for those: [registerService] walks the service schema's
     * `relationSchemas` and fills this from `serviceDataRelation(id, type,
     * maintained = true)`. A module author describes *what the relation is*, on the
     * schema; nothing they write names an observer.
     *
     * The **entity-scoped** relations ([registerRelationMembership]) are the one
     * exception, for the reason §9.2.1 states: they are not service relations, so
     * no `ServiceSchema.relationSchemas` walk can reach them.
     */
    val relationSpecs: List<org.tenshu.core.common.relation.RelationSpec>

    fun registerComposite(
        interfaceClass: KClass<out Data>,
        dataSchema: CompositeSchema,
        dtoFactory: (fields: List<Data?>) -> Data,
        roFactory: ((RequestChain, TContext) -> Data)? = null
    )

    fun registerSchema(dataSchema: DataSchema)

    fun registerConstant(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        dtoFactory: () -> Data,
        roFactory: ((RequestChain, TContext) -> Data)? = null
    )

    fun registerValue(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        dtoFactory: (value: Any) -> Data,
        roFactory: ((RequestChain, TContext) -> Data)? = null
    )

    fun registerType(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        roFactory: ((RequestChain, TContext) -> Data)? = null
    )

    /**
     * Like [registerType], but the factory receives the requested [Type] so it can read
     * parameterisation (e.g. `TList<E>`'s element schema). Used by parameterised types —
     * `TList`, `Maybe` — whose ROs need to know their element type at construction time.
     * Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`.
     */
    fun registerParameterisedType(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        roFactory: (RequestChain, TContext, Type) -> Data
    )

    /**
     * Like [registerComposite], but the factory receives the requested [Type] so it can
     * read parameterisation. Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`.
     */
    fun registerParameterisedComposite(
        interfaceClass: KClass<out Data>,
        dataSchema: CompositeSchema,
        dtoFactory: (fields: List<Data?>) -> Data,
        roFactory: (RequestChain, TContext, Type) -> Data
    )

    fun registerOperation(
        interfaceClass: KClass<out Data>,
        protocolSchema: OperationSchema,
        dtoFactory: ((fields: List<Data?>) -> Data)? = null,
        roFactory: ((RequestChain, TContext) -> Data)? = null
    )

    fun registerService(
        serviceSchema: ServiceSchema
    )

    fun registerServiceRelation(
        fromId: String,
        targetId: String
    )

    fun registerServiceDataRelation(
        fromId: String,
        targetId: String,
        relation: org.tenshu.core.relation.ServiceDataRelation
    )

    fun registerPropertyRelation(
        fromId: String,
        schema: DataSchema
    )

    /**
     * Registers a stored value property — a property of [fromId] whose value is a
     * plain scalar (`TInteger` / `TInstant` / `TString`) backed by the
     * [valueSchema] (id = the property id, parent = the underlying value type).
     * Unlike [registerValue] this does NOT touch the class→type mapping, so the
     * underlying value type's mapping is undisturbed. See
     * `docs/adr/0013-property-authoring-unification.md`.
     */
    fun registerValueProperty(
        fromId: String,
        valueSchema: org.tenshu.core.schema.ValueSchema,
        valueFactory: (value: Any) -> Data
    )

    fun registerDynamicPropertyRelation(
        fromId: String,
        schema: RelationSchema.DynamicPropertyRelationSchema
    )

    /**
     * Registers a **reference value property** of [fromId] — a static property whose value
     * is resolved live from the foreign entity [targetTypeId], keyed by the receiver's own
     * [keyFieldRef] field. Emits the same `StaticPropertyRelation` selector as any value
     * property; the (key field, target type) pair is held proxy-side for resolution only
     * (never serialised). See `docs/proposals/core-reference-property.md`.
     */
    fun registerReferenceProperty(
        fromId: String,
        propertyId: String,
        keyFieldRef: String,
        targetTypeId: String
    )

    /**
     * Registers an **entity relation** of [fromId] — a static, list-valued property
     * whose elements ([elementTypeId]) live in a per-entity array keyed by the
     * receiver's own `DataId` (an `EntityPropertyDataId`). Emits the same
     * `StaticPropertyRelation` selector as any value property; the element type is
     * held proxy-side for resolution only (never serialised). The one-to-many
     * counterpart of [registerReferenceProperty]. See
     * `docs/proposals/core-nested-projection.md` §3.3.
     */
    fun registerEntityRelation(
        fromId: String,
        propertyId: String,
        elementTypeId: String
    )

    fun registerTrait(
        dataSchema: DataSchema,
        traitSchema: TraitSchema,
        traitFactory: (TContext, TraitSchema) -> Trait
    )

    /**
     * Registers that [fromId]'s value [property] fulfils the value trait requirement
     * [requirementId] (e.g. `File.CreatedAt` fulfils `RealmData.CREATED_AT`). Resolved via
     * [SchemaProvider.resolveValueRequirement][org.tenshu.core.core.SchemaProvider.resolveValueRequirement].
     * The binding must be explicit because a type may carry several value properties of the
     * required value type (`File` has both `CreatedAt` and `LastModifiedAt`) —
     * `docs/proposals/core-realm-data.md` §8.
     */
    fun registerValueRequirement(
        fromId: String,
        requirementId: String,
        property: org.tenshu.core.relation.Property.Value<*>,
    )

    /** Accumulates into [schemaMigrations]; use the [schemaMigration] DSL extension. */
    fun registerSchemaMigration(migration: SchemaMigration)

    /**
     * Accumulates an **entity-scoped** relation's membership rule into
     * [relationSpecs]; use the
     * [relationMembership][org.tenshu.core.common.module.relationMembership] DSL
     * extension, which carries the "why here and not on the schema" rationale.
     */
    fun registerRelationMembership(spec: org.tenshu.core.common.relation.RelationSpec)

    fun include(config: ModuleConfiguration)
}
