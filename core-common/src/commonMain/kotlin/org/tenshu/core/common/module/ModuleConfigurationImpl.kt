package org.tenshu.core.common.module

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.CompositeTypeReferenceDto
import org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.impl.reference.TypeReferenceDto
import org.tenshu.core.common.impl.reference.ValueTypeReferenceDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.TContext
import org.tenshu.core.reference.EntityListReference
import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.EntityRelationDescriptor
import org.tenshu.core.relation.ReferencePropertyDescriptor
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.LazyComposite
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.elementSchema
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema
import kotlin.reflect.KClass
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.toTString

class ModuleConfigurationImpl : ModuleConfiguration {

    private val _prerequisites = mutableListOf<ModuleConfiguration>()
    override val prerequisites: List<ModuleConfiguration> get() = _prerequisites

    override val typeMapping: MutableMap<ByteArray, String> = mutableMapOf()

    override val classToTypeMapping: MutableMap<KClass<out Data>, String> = mutableMapOf()

    override val referenceMapping: MutableMap<String, Reference> = mutableMapOf()

    override val schemaMapping: MutableMap<String, DataSchema> = mutableMapOf()

    override val relations: MutableMap<List<String>, String> = mutableMapOf()

    override val serviceDataRelations: MutableMap<Pair<ServiceTypeReference, Reference>, org.tenshu.core.relation.ServiceDataRelation> =
        mutableMapOf()

    override val traitFactories: MutableMap<Pair<String, String>, (TContext, TraitSchema) -> Trait> =
        mutableMapOf()

    override val constantFactories: MutableMap<String, () -> Data> = mutableMapOf()

    override val valueFactories: MutableMap<String, (value: Any) -> Data> = mutableMapOf()

    override val referenceProperties: MutableMap<String, ReferencePropertyDescriptor> =
        mutableMapOf()

    override val entityRelations: MutableMap<String, EntityRelationDescriptor> =
        mutableMapOf()

    override val valueRequirementBindings:
        MutableMap<Pair<String, String>, org.tenshu.core.relation.Property.Value<*>> =
        mutableMapOf()

    override val compositeFactories: MutableMap<String, (fields: List<Data?>) -> Data> =
        mutableMapOf()

    override val requestObjectFactories: MutableMap<String, (RequestChain, TContext, Type) -> Data> =
        mutableMapOf()

    override val operations: MutableMap<String, MutableMap<String, (RequestChain, TContext) -> Operation>> =
        mutableMapOf()

    private val _schemaMigrations = mutableListOf<org.tenshu.core.common.schema.migration.SchemaMigration>()
    override val schemaMigrations: List<org.tenshu.core.common.schema.migration.SchemaMigration>
        get() = _schemaMigrations

    private val _relationSpecs = mutableListOf<org.tenshu.core.common.relation.RelationSpec>()
    override val relationSpecs: List<org.tenshu.core.common.relation.RelationSpec>
        get() = _relationSpecs

    override fun registerComposite(
        interfaceClass: KClass<out Data>,
        dataSchema: CompositeSchema,
        dtoFactory: (fields: List<Data?>) -> Data,
        roFactory: ((RequestChain, TContext) -> Data)?
    ) {
        registerCompositeInternal(
            interfaceClass = interfaceClass,
            dataSchema = dataSchema,
            factory = dtoFactory,
            requestObjectFactory = roFactory?.toParameterised()
        )
    }

    override fun registerParameterisedComposite(
        interfaceClass: KClass<out Data>,
        dataSchema: CompositeSchema,
        dtoFactory: (fields: List<Data?>) -> Data,
        roFactory: (RequestChain, TContext, Type) -> Data
    ) {
        registerCompositeInternal(
            interfaceClass = interfaceClass,
            dataSchema = dataSchema,
            factory = dtoFactory,
            requestObjectFactory = roFactory
        )
    }

    private fun registerCompositeInternal(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        factory: (fields: List<Data?>) -> Data,
        requestObjectFactory: ((RequestChain, TContext, Type) -> Data)? = null
    ) {
        val idString = dataSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(dataSchema)

        compositeFactories[idString] = factory
        if (requestObjectFactory != null) {
            requestObjectFactories[idString] = requestObjectFactory
        }
    }

    override fun registerSchema(dataSchema: DataSchema) {
        val idString = dataSchema.id.get()
        typeMapping[idToUuid(idString)] = idString
        schemaMapping[idString] = dataSchema

        registerReference(idString, createReference(dataSchema))

        registerFields(dataSchema)
    }

    private fun registerReference(id: String, reference: Reference) {
        val existingReference = referenceMapping[id]

        if (existingReference == null || getWeight(reference) > getWeight(existingReference)) {
            referenceMapping[id] = reference
        }
    }

    private fun getWeight(reference: Reference): Int {
        return when (reference) {
            is DynamicPropertyTypeReferenceDto -> 3
            is EntityReferenceDto -> 3
            is EntityListReferenceDto -> 3
            is ServiceTypeReferenceDto -> 3
            is FieldReferenceDto -> 3
            is PropertyTypeReferenceDto -> 2
            is CompositeTypeReferenceDto -> 1
            is ValueTypeReferenceDto -> 1
            is TypeReferenceDto -> 0
            else -> -1
        }
    }

    private fun createReference(dataSchema: DataSchema): Reference {
        val idString = dataSchema.id.get()
        return when (dataSchema) {
            is RelationSchema.DynamicPropertyRelationSchema -> DynamicPropertyTypeReferenceDto(
                idString
            )

            is RelationSchema.ServiceDataRelationSchema -> EntityReferenceDto(idString)
            is RelationSchema.PropertyRelationSchema -> PropertyTypeReferenceDto(idString)
            is RelationSchema.ServiceRelationSchema -> ServiceTypeReferenceDto(idString)
            is ServiceSchema -> ServiceTypeReferenceDto(idString)
            is CompositeSchema -> CompositeTypeReferenceDto(idString)
            is ValueSchema -> ValueTypeReferenceDto(idString)
            else -> TypeReferenceDto(idString)
        }
    }

    private fun registerFields(dataSchema: DataSchema) {
        if (dataSchema is CompositeSchema) {
            dataSchema.values.toList().forEach { field ->
                val fieldId = field.id.get()
                typeMapping[idToUuid(fieldId)] = fieldId
                registerReference(fieldId, FieldReferenceDto(fieldId))
                if (schemaMapping[fieldId] == null) {
                    schemaMapping[fieldId] = simpleSchema(
                        fieldId,
                        parent = DataSchema.Schema,
                        schemaParam = Field.Schema
                    )
                }
            }
        }
    }

    override fun registerConstant(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        dtoFactory: () -> Data,
        roFactory: ((RequestChain, TContext) -> Data)?
    ) {
        val idString = dataSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(dataSchema)
        constantFactories[idString] = dtoFactory
        if (roFactory != null) {
            requestObjectFactories[idString] = roFactory.toParameterised()
        }
    }

    override fun registerValue(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        dtoFactory: (value: Any) -> Data,
        roFactory: ((RequestChain, TContext) -> Data)?
    ) {
        val idString = dataSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(dataSchema)

        valueFactories[idString] = dtoFactory
        if (roFactory != null) {
            requestObjectFactories[idString] = roFactory.toParameterised()
        }
    }

    override fun registerType(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        roFactory: ((RequestChain, TContext) -> Data)?
    ) {
        val idString = dataSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(dataSchema)
        if (roFactory != null) {
            requestObjectFactories[idString] = roFactory.toParameterised()
        }
    }

    override fun registerParameterisedType(
        interfaceClass: KClass<out Data>,
        dataSchema: DataSchema,
        roFactory: (RequestChain, TContext, Type) -> Data
    ) {
        val idString = dataSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(dataSchema)
        requestObjectFactories[idString] = roFactory
    }

    override fun registerOperation(
        interfaceClass: KClass<out Data>,
        protocolSchema: OperationSchema,
        dtoFactory: ((fields: List<Data?>) -> Data)?,
        roFactory: ((RequestChain, TContext) -> Data)?
    ) {
        val idString = protocolSchema.id.get()
        classToTypeMapping[interfaceClass] = idString
        registerSchema(protocolSchema)

        if (dtoFactory != null) {
            compositeFactories[idString] = dtoFactory
        } else {
            compositeFactories[idString] = { fields ->
                val params = protocolSchema.values.toList().mapIndexedNotNull { index, field ->
                    val value = fields.getOrNull(index) ?: return@mapIndexedNotNull null
                    field.id.get() to value
                }.toMap()
                OperationDto(params, protocolSchema)
            }
        }

        if (roFactory != null) {
            requestObjectFactories[idString] = roFactory.toParameterised()
        }

        // Phase 2.2 of `docs/adr/0009-operation-schema-typing.md`: when the schema declares a
        // receiver, slot the factory into `operations[derivedTypeId][opId]` so the
        // single-arg `findOperation(opId)` can route through it without a separate
        // `baseTypeId` argument. The two-arg `registerOperation` overload still exists
        // and takes precedence when callers pass `baseTypeId` explicitly; this branch
        // only fires when the no-baseTypeId overload was used AND a receiver is declared.
        val derivedTypeId = protocolSchema.receiver?.baseSchema?.id?.get()
        if (derivedTypeId != null && roFactory != null) {
            @Suppress("UNCHECKED_CAST")
            operations.getOrPut(derivedTypeId) { mutableMapOf() }[idString] =
                roFactory as (RequestChain, TContext) -> Operation
        }
    }

    override fun registerService(
        serviceSchema: ServiceSchema
    ) {
        val serviceId = serviceSchema.id.get()
        registerSchema(serviceSchema)

        serviceSchema.relationSchemas.toList().forEach { relation ->
            relations[listOf(serviceId, relation.id.get())] = relation.id.get()
            registerSchema(relation)
            if (relation is RelationSchema.ServiceDataRelationSchema) {
                registerDeclaredRelation(serviceId, relation)
            }
        }
    }

    /**
     * Derives a relation's registrations from its own schema declaration
     * (`core-relation-system.md` §9.2.1) — the chain reference when
     * [RelationSchema.ServiceDataRelationSchema.chainReadable], and the
     * maintenance entry when it declares itself
     * [maintained][RelationSchema.ServiceDataRelationSchema.maintained]. The schema already
     * carries the relation id and, via `Type.elementSchema()`, the element type,
     * so nothing is restated.
     *
     * **Additive and non-clobbering**, because R1 migrates only two services and
     * the other twelve still register by hand:
     *  - the `serviceDataRelations` map is only *filled in*, never overwritten, so
     *    a hand-written registration wins in either order (a module that calls
     *    `service(...)` first — all of them do — then overwrites this with an
     *    identical value; one that called it the other way round would still keep
     *    its own);
     *  - a spec is added only once per `(elementType, relation)`.
     *
     * ⚠ **Collection relations only.** The single-entity form
     * (`FileSystem.ROOT`: `type = Folder.Schema.toType()` plus a concrete
     * `dataId`) needs a `dataId` the schema does **not** carry, so the schema
     * cannot describe it and deriving one would produce a relation that fails at
     * resolve. Those keep their hand-written registration permanently — a real
     * limit of §9.2.1, not a phase boundary.
     */
    private fun registerDeclaredRelation(
        serviceId: String,
        relation: RelationSchema.ServiceDataRelationSchema,
    ) {
        if (relation.type.baseSchema?.id?.get() != TList.ID) return

        val relationId = relation.id.get()
        val elementSchema = relation.type.elementSchema()

        if (relation.chainReadable) {
            val targetRef = EntityListReferenceDto(relationId)
            val key = ServiceTypeReferenceDto(serviceId) to (targetRef as Reference)
            if (key !in serviceDataRelations) {
                serviceDataRelations[key] = org.tenshu.core.relation.ServiceDataRelation.create(
                    from = serviceId,
                    to = relationId,
                    type = relation.type,
                )
                registerReference(relationId, targetRef)
            }
        }

        if (!relation.maintained) return
        val spec = org.tenshu.core.common.relation.RelationSpec(
            elementTypeId = elementSchema.id.get(),
            scope = org.tenshu.core.common.relation.RelationScope.Service(relationId),
            // The SAME derivation `ReferenceResolver.resolveServiceData` uses for
            // the read side, so the two cannot drift apart.
            isEntity = elementSchema.isSubtypeOf(org.tenshu.core.entity.Entity.ID),
        )
        if (spec !in _relationSpecs) _relationSpecs += spec
    }

    override fun registerServiceRelation(
        fromId: String,
        targetId: String
    ) {
        registerPlaceholderSchema(
            targetId,
            RelationSchema.Schema,
            RelationSchema.PropertyRelationSchema.Schema
        )
        relations[listOf(fromId, targetId)] = targetId
        registerReference(targetId, ServiceTypeReferenceDto(targetId))
    }

    private fun registerPlaceholderSchema(
        id: String,
        parent: DataSchema? = null,
        schema: CompositeSchema = CompositeSchema.Schema
    ) {
        typeMapping[idToUuid(id)] = id
        if (schemaMapping[id] == null) {
            schemaMapping[id] = simpleSchema(id, parent, schema)
        }
    }

    override fun registerServiceDataRelation(
        fromId: String,
        targetId: String,
        relation: org.tenshu.core.relation.ServiceDataRelation
    ) {
        registerPlaceholderSchema(
            targetId,
            RelationSchema.Schema,
            RelationSchema.ServiceDataRelationSchema.Schema
        )
        // Reference type follows the relation's target type — list types get an
        // EntityListReference (for collection-by-type lookups), single composites
        // get an EntityReference (for entity-by-id lookups). Phase 5 may unify
        // these into a single reference type once the migration window closes.
        val targetRef: Reference = if (relation.type.baseSchema?.id?.get() == TList.ID) {
            EntityListReferenceDto(targetId)
        } else {
            EntityReferenceDto(targetId)
        }
        serviceDataRelations[ServiceTypeReferenceDto(fromId) to targetRef] = relation
        registerReference(targetId, targetRef)
    }

    override fun registerPropertyRelation(
        fromId: String,
        schema: DataSchema
    ) {
        val idString = schema.id.get()
        registerSchema(schema)
        relations[listOf(fromId, idString)] = idString
        registerReference(idString, PropertyTypeReferenceDto(idString))
    }

    override fun registerValueProperty(
        fromId: String,
        valueSchema: org.tenshu.core.schema.ValueSchema,
        valueFactory: (value: Any) -> Data
    ) {
        val idString = valueSchema.id.get()
        // Schema + value factory so the dbEntryMapper's `is ValueSchema` property
        // branch and `createValue(idString, raw)` both resolve — but NO
        // classToTypeMapping write (that's the difference from registerValue;
        // the property has no dedicated Kotlin interface). See
        // docs/adr/0013-property-authoring-unification.md.
        registerSchema(valueSchema)
        valueFactories[idString] = valueFactory
        relations[listOf(fromId, idString)] = idString
        registerReference(idString, PropertyTypeReferenceDto(idString))
    }

    override fun registerDynamicPropertyRelation(
        fromId: String,
        schema: RelationSchema.DynamicPropertyRelationSchema
    ) {
        val idString = schema.id.get()
        registerSchema(schema)
        relations[listOf(fromId, idString)] = idString
        registerReference(idString, DynamicPropertyTypeReferenceDto(idString))
    }

    override fun registerReferenceProperty(
        fromId: String,
        propertyId: String,
        keyFieldRef: String,
        targetTypeId: String
    ) {
        // Static selector (same as any value property); the (keyField, target) pair is
        // a proxy-side resolution detail, never serialised. See
        // docs/proposals/core-reference-property.md.
        //
        // The propertyId MUST be in typeMapping or the parser can't decode the
        // reference's id hash off the wire (`getTypeId(bytes)` → "No type id
        // registered for bytes"). Unlike `registerValueProperty`, this has no
        // schema to `registerSchema`, so add the mapping explicitly — without it
        // the `relations(handle)` projection silently fails the whole read.
        typeMapping[idToUuid(propertyId)] = propertyId
        relations[listOf(fromId, propertyId)] = propertyId
        registerReference(propertyId, PropertyTypeReferenceDto(propertyId))
        referenceProperties[propertyId] =
            ReferencePropertyDescriptor(keyFieldRef, targetTypeId)
    }

    override fun registerEntityRelation(
        fromId: String,
        propertyId: String,
        elementTypeId: String
    ) {
        // Static selector (same as any value property); the element type is a
        // proxy-side resolution detail, never serialised. See
        // docs/proposals/core-nested-projection.md §3.3.
        //
        // The propertyId MUST be in typeMapping or the parser can't decode the
        // reference's id hash off the wire (`getTypeId(bytes)` → "No type id
        // registered for bytes"). Unlike `registerValueProperty`, this has no
        // schema to `registerSchema`, so add the mapping explicitly — without it
        // the `.relation(handle)` chain read silently fails over IPC.
        typeMapping[idToUuid(propertyId)] = propertyId
        relations[listOf(fromId, propertyId)] = propertyId
        registerReference(propertyId, PropertyTypeReferenceDto(propertyId))
        entityRelations[propertyId] = EntityRelationDescriptor(elementTypeId)
    }

    override fun registerTrait(
        dataSchema: DataSchema,
        traitSchema: TraitSchema,
        traitFactory: (TContext, TraitSchema) -> Trait
    ) {
        registerSchema(traitSchema)
        traitFactories[Pair(dataSchema.id.get(), traitSchema.id.get())] = traitFactory
    }

    override fun registerValueRequirement(
        fromId: String,
        requirementId: String,
        property: org.tenshu.core.relation.Property.Value<*>,
    ) {
        // Pure proxy-side binding (a Kotlin handle); never serialised. See
        // docs/proposals/core-realm-data.md §8.
        valueRequirementBindings[Pair(fromId, requirementId)] = property
    }

    override fun registerSchemaMigration(migration: org.tenshu.core.common.schema.migration.SchemaMigration) {
        _schemaMigrations += migration
    }

    override fun registerRelationMembership(spec: org.tenshu.core.common.relation.RelationSpec) {
        if (spec !in _relationSpecs) _relationSpecs += spec
    }

    override fun include(config: ModuleConfiguration) {
        // Phase 3 of core-module-composition.md: prerequisites is now the
        // canonical record of "this module depends on that one";
        // `SchemaRegistry.registerConfig` walks the chain depth-first at
        // registration time. The eager putAll merge that used to live here
        // is gone — modules are atomic, their content lives only in their
        // own maps, and `firstNotNullOfOrNull(configs.asReversed())` lookups
        // still find every prerequisite's entries because each prerequisite
        // is now its own configs entry.
        if (config !in _prerequisites) {
            _prerequisites += config
        }
    }

    fun simpleSchema(
        id: String,
        parent: DataSchema? = null,
        schemaParam: CompositeSchema = CompositeSchema.Schema
    ): CompositeSchema =
        object : LazyComposite<CompositeSchema>(
            materializeFunc = { context ->
                object : CompositeSchema {
                    override val id: TString get() = id.toTString(context)
                    override val parent: DataSchema? get() = parent
                    override val schema get() = schemaParam
                    override val traits: TList<TraitSchema>
                        get() = emptyList<TraitSchema>().toTList(
                            context
                        )
                    override val values: TList<Field> get() = emptyList<Field>().toTList(context)

                    override fun <T : Data> getField(reference: String): T =
                        error("SimpleSchema does not support getField: $reference")

                    override fun <T : Data> getOptionalField(reference: String): T? = null
                }
            },
            lazyFields = {
                mapOf(
                    DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                    DataSchema.Fields.PARENT to parent,
                    CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
                    CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>()),
                    CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(
                        emptyList<org.tenshu.core.schema.TypeParameterDecl>()
                    )
                )
            }
        ), CompositeSchema {
            override val schema get() = schemaParam
            override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
            override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        }
}

/**
 * Adapts a 2-arg `(chain, ctx) -> Data` factory into the 3-arg storage shape expected by
 * `requestObjectFactories`. Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`: parameterised
 * types (TList, Maybe) register via [ModuleConfiguration.registerParameterisedType] /
 * [ModuleConfiguration.registerParameterisedComposite], which already produce 3-arg
 * factories; everything else uses this adapter so call sites stay unchanged.
 */
private fun ((RequestChain, TContext) -> Data).toParameterised(): (RequestChain, TContext, Type) -> Data =
    { chain, ctx, _ -> this(chain, ctx) }

private class SimpleTString(private val valueStr: String) : TString {
    override fun get(): String = valueStr
    override val value: Data get() = this
    override val schema: ValueSchema get() = TString.Schema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TString) return false
        return valueStr == other.get()
    }

    override fun hashCode(): Int = valueStr.hashCode()
    override fun toString(): String = valueStr
}
