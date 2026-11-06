package org.tenshu.core.common.module

import org.tenshu.core.Data
import org.tenshu.core.common.relation.RelationSpec
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.core.TContext
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.relation.Property
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.UnionSchema
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema
import kotlin.reflect.KClass

fun tModule(block: ModuleConfiguration.() -> Unit): ModuleConfiguration {
    return ModuleConfigurationImpl().apply(block)
}

fun ModuleConfiguration.composite(dataSchema: CompositeSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.value(dataSchema: ValueSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.constant(dataSchema: ConstantSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.operation(dataSchema: OperationSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.union(dataSchema: UnionSchema) = registerSchema(dataSchema)

/**
 * Registers a service's schema **and its declared relations**. Each
 * `ServiceDataRelationSchema` in [ServiceSchema.relationSchemas] gets its chain reference
 * and, when it is declared `maintained`, its maintenance entry, both derived from the
 * declaration itself (`core-relation-system.md` §9.2.1). Declare a relation once, on the
 * schema; a hand-written `serviceDataRelation(fromId, targetId, …)` beside it is redundant.
 *
 * ⚠ Only this overload walks the relations. The `service(interfaceClass, serviceSchema,
 * roFactory)` overload attaches the RO factory and nothing else, so a module needs both.
 */
fun ModuleConfiguration.service(
    dataSchema: ServiceSchema
) = registerService(dataSchema)

fun ModuleConfiguration.trait(dataSchema: TraitSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.type(dataSchema: DataSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.reference(dataSchema: ReferenceSchema) = registerSchema(dataSchema)

fun ModuleConfiguration.composite(
    interfaceClass: KClass<out Data>,
    dataSchema: CompositeSchema,
    dtoFactory: (fields: List<Data?>) -> Data,
    roFactory: ((RequestChain, TContext) -> Data)? = null,
) = registerComposite(interfaceClass, dataSchema, dtoFactory, roFactory)

fun ModuleConfiguration.constant(
    interfaceClass: KClass<out Data>,
    dataSchema: DataSchema,
    dtoFactory: () -> Data,
    roFactory: ((RequestChain, TContext) -> Data)? = null
) = registerConstant(interfaceClass, dataSchema, dtoFactory, roFactory)

fun ModuleConfiguration.value(
    interfaceClass: KClass<out Data>,
    dataSchema: DataSchema,
    dtoFactory: (value: Any) -> Data,
    roFactory: ((RequestChain, TContext) -> Data)? = null
) = registerValue(interfaceClass, dataSchema, dtoFactory, roFactory)

fun ModuleConfiguration.type(
    interfaceClass: KClass<out Data>,
    dataSchema: DataSchema,
    roFactory: ((RequestChain, TContext) -> Data)? = null
) = registerType(interfaceClass, dataSchema, roFactory)

/**
 * DSL shorthand for [ModuleConfiguration.registerParameterisedType] — `roFactory` receives
 * the requested [Type] so parameterised types (`TList<E>`, `Maybe<E>`) can read their
 * element schema from `type.params` at construction time.
 * Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`.
 */
fun ModuleConfiguration.parameterisedType(
    interfaceClass: KClass<out Data>,
    dataSchema: DataSchema,
    roFactory: (RequestChain, TContext, Type) -> Data
) = registerParameterisedType(interfaceClass, dataSchema, roFactory)

/**
 * DSL shorthand for [ModuleConfiguration.registerParameterisedComposite].
 * Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`.
 */
fun ModuleConfiguration.parameterisedComposite(
    interfaceClass: KClass<out Data>,
    dataSchema: CompositeSchema,
    dtoFactory: (fields: List<Data?>) -> Data,
    roFactory: (RequestChain, TContext, Type) -> Data
) = registerParameterisedComposite(interfaceClass, dataSchema, dtoFactory, roFactory)

fun ModuleConfiguration.operation(
    interfaceClass: KClass<out Data>,
    protocolSchema: OperationSchema,
    dtoFactory: ((fields: List<Data?>) -> Data)? = null,
    roFactory: ((RequestChain, TContext) -> Data)? = null
) = registerOperation(interfaceClass, protocolSchema, dtoFactory, roFactory)

fun ModuleConfiguration.service(
    interfaceClass: KClass<out Data>,
    serviceSchema: ServiceSchema,
    roFactory: ((RequestChain, TContext) -> Data)? = null
) = registerType(interfaceClass, serviceSchema, roFactory)

fun ModuleConfiguration.serviceRelation(
    fromId: String,
    targetId: String
) = registerServiceRelation(fromId, targetId)

fun ModuleConfiguration.serviceDataRelation(
    fromId: String,
    targetId: String,
    relation: org.tenshu.core.relation.ServiceDataRelation
) = registerServiceDataRelation(fromId, targetId, relation)

fun ModuleConfiguration.propertyRelation(
    fromId: String,
    schema: DataSchema
) = registerPropertyRelation(fromId, schema)

/**
 * Declares + registers an **op-written** static value property of [fromId] from
 * its typed [property] handle: a `ValueSchema` (id = `property.id`, parent =
 * `property.schema`), its [valueFactory], and the property relation. Use this
 * shape for stored scalars (`File.Size`, `File.LastModifiedAt`) whose value the
 * engine writes via `createValue`. The handle is the single source of truth for
 * the property across declaration, write (`set`), read (`get`), projection, and
 * sort. See `docs/adr/0013-property-authoring-unification.md`.
 */
fun ModuleConfiguration.valueProperty(
    fromId: String,
    property: Property.Value<*>,
    valueFactory: (value: Any) -> Data,
) = registerValueProperty(
    fromId = fromId,
    valueSchema = org.tenshu.core.schema.value(id = property.id, parent = property.schema as ValueSchema),
    valueFactory = valueFactory,
)

/**
 * Registers a **constant / singleton** static value property of [fromId] from
 * its typed [property] handle — no value factory: the value is the handle's own
 * already-registered [schema] (`File.Category` → `FileCategory.Schema`), resolved
 * as a constant rather than op-written. The constant-case counterpart of the
 * [valueFactory]-bearing overload; both produce the same `StaticPropertyRelation` wire
 * selector. See `docs/adr/0013-property-authoring-unification.md`.
 */
fun ModuleConfiguration.valueProperty(
    fromId: String,
    property: Property.Value<*>,
) = registerPropertyRelation(fromId, property.schema)

/**
 * Registers a **reference value property** of [fromId] from its typed [property] handle —
 * the handle is an ordinary `Property.Value<T>` whose `schema` is the foreign entity's
 * [CompositeSchema][org.tenshu.core.schema.CompositeSchema] (e.g.
 * `valueProperty<Device>(id, Device.Schema)`). [keyField] names the receiver's own field
 * that holds the target's key (e.g. `FileModification.Field.DEVICE_ID`); resolution builds
 * `StringDataId(target, <keyField>)` and dereferences via `Database.getData`. Same
 * `StaticPropertyRelation` wire selector as any value property — the reference-ness is a
 * proxy-side detail. See `docs/proposals/core-reference-property.md`.
 */
fun ModuleConfiguration.referenceProperty(
    fromId: String,
    property: Property.Value<*>,
    keyField: String,
) = registerReferenceProperty(
    fromId = fromId,
    propertyId = property.id,
    keyFieldRef = keyField,
    targetTypeId = property.schema.id.get(),
)

/**
 * Registers an **entity relation** of [fromId] from its typed [property] handle — built
 * by `entityRelation(id, element)`, whose `schema` is the array's **element**
 * [CompositeSchema][org.tenshu.core.schema.CompositeSchema] (e.g.
 * `entityRelation<FileModification>(id, FileModification.Schema)`). Resolution reads the
 * receiver's per-entity array `EntityPropertyDataId(<receiver DataId>, <id>)` via
 * `Database.getArrayData`. Same `StaticPropertyRelation` wire selector as any value
 * property — the list-ness + per-entity keying is a proxy-side detail. The one-to-many
 * counterpart of [referenceProperty]. See `docs/proposals/core-nested-projection.md` §3.3.
 */
fun ModuleConfiguration.entityRelation(
    fromId: String,
    property: Property.Value<*>,
) = registerEntityRelation(
    fromId = fromId,
    propertyId = property.id,
    elementTypeId = property.schema.id.get(),
)

fun ModuleConfiguration.dynamicPropertyRelation(
    fromId: String,
    schema: RelationSchema.DynamicPropertyRelationSchema
) = registerDynamicPropertyRelation(fromId, schema)

/**
 * Declares + registers a dynamic (chain-computed) property of [fromId] from its
 * typed [property] handle — registers the handle's carried
 * `DynamicPropertyRelationSchema` (with its `ChainTemplate`). The dynamic-kind
 * counterpart of [valueProperty]. See
 * `docs/adr/0013-property-authoring-unification.md`.
 */
fun ModuleConfiguration.dynamicProperty(
    fromId: String,
    property: Property.Dynamic<*>,
) = registerDynamicPropertyRelation(fromId, property.relationSchema)

fun ModuleConfiguration.trait(
    dataSchema: DataSchema,
    traitSchema: TraitSchema,
    traitFactory: (TContext, TraitSchema) -> Trait
) = registerTrait(dataSchema, traitSchema, traitFactory)

/**
 * Registers a service version bump's
 * [SchemaMigration][SchemaMigration]
 * (`core-schema-evolution.md` §8.4). Lives in the service's `tModule { }`
 * beside its `service(id, version = …)` declaration so the schema Δ and its
 * migration land on the same screenful; the readable floor is DERIVED from
 * the registered chain (a non-contiguous chain fails at init —
 * [org.tenshu.core.common.schema.migration.SchemaMigrationRegistry.verify]).
 */
fun ModuleConfiguration.schemaMigration(
    migration: SchemaMigration
) = registerSchemaMigration(migration)

/**
 * Declares the membership rule of an **entity-scoped** relation — the per-entity
 * arrays the `serviceDataRelation` schema walk cannot reach
 * (`core-relation-system.md` §9.2.1, phase R2).
 *
 * A *service* relation never comes through here: it declares on its own service
 * schema (`serviceDataRelation(id, type, maintained = true)`) and [registerService]
 * derives the spec from it, because the schema already carries the relation id
 * and — via `Type.elementSchema()` — the element type. An entity-scoped relation
 * carries neither of those facts on any schema:
 *
 *  - `Folder.FILES` is a list **field** on a composite and `File.FileHistory` is a
 *    **property**, so neither appears in `ServiceSchema.relationSchemas`;
 *  - the *parent* an element belongs under is derived from the element's own key
 *    by string surgery (`path.substringBeforeLast("/")`,
 *    `compositeKey.substringBefore("|")`), which is irreducibly code — there is
 *    nothing a schema could carry. §9.2.1 anticipated this ("they declare at
 *    their own sites"); the module configuration *is* that site, beside the
 *    element type's own registration.
 *
 * ⚠ The `parentOf` lambda is fed the saved row on the save path and the deleted
 * element's own `DataId` on the delete path — see
 * [org.tenshu.core.common.relation.RelationScope.EntityField]. Deriving the
 * parent from `ExecutionContext.getDataId(element)` (which returns a `DataId`
 * unchanged) handles both in one expression.
 */
fun ModuleConfiguration.relationMembership(
    spec: RelationSpec
) = registerRelationMembership(spec)

/**
 * Declares that [fromId]'s value [property] fulfils the value trait requirement
 * [requirementId] (e.g. `fulfilValueRequirement(File.ID, RealmData.CREATED_AT, File.CreatedAt)`).
 * Resolved via `SchemaProvider.resolveValueRequirement`; the binding is explicit because a
 * type may carry several properties of the required value type — `core-realm-data.md` §8.
 */
fun ModuleConfiguration.fulfilValueRequirement(
    fromId: String,
    requirementId: String,
    property: Property.Value<*>,
) = registerValueRequirement(fromId, requirementId, property)
