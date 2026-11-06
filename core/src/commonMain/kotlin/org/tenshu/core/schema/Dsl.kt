package org.tenshu.core.schema

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.SimpleTString
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TConstant
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.TMap
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.create
import org.tenshu.core.core.dataFactory
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.SimpleRequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.CompositeDataIdProviderSchema
import org.tenshu.core.entity.DataIdProviderSchema
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.EntityListReference
import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.reference.SimpleDynamicPropertyReference
import org.tenshu.core.reference.SimpleEntityListReference
import org.tenshu.core.reference.SimpleEntityReference
import org.tenshu.core.reference.SimpleFieldReference
import org.tenshu.core.reference.SimplePropertyReference
import org.tenshu.core.reference.SimpleServiceReference
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.toTString
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitRequirementSchema
import org.tenshu.core.trait.TraitSchema
import org.tenshu.core.trait.ValueTraitRequirement
import org.tenshu.core.trait.ValueTraitRequirementSchema

fun fieldRef(id: String): FieldReference = SimpleFieldReference(id)
fun propertyRef(id: String): PropertyTypeReference = SimplePropertyReference(id)
fun dynamicPropertyRef(id: String): DynamicPropertyTypeReference =
    SimpleDynamicPropertyReference(id)

fun entityRef(id: String): EntityReference = SimpleEntityReference(id)
fun entityListRef(id: String): EntityListReference = SimpleEntityListReference(id)
fun serviceRef(id: String): ServiceTypeReference = SimpleServiceReference(id)

fun stringDataId(
    id: String,
    fieldRef: String,
): StringDataIdProviderSchema = object : LazyComposite<StringDataIdProviderSchema>(
    materializeFunc = { context ->
        object : StringDataIdProviderSchema {
            override val id: TString = id.toTString(context)
            override val fieldRef: TString = fieldRef.toTString(context)
            override val parent: DataSchema = CompositeDataIdProvider.Schema

            @Suppress("UNCHECKED_CAST")
            override val traitRequirements = listOf(
                GetStringDataId.Schema
            ).toTList(context) as TList<TraitRequirementSchema>

            override val values: TList<Field> = listOf(
                Field.create(
                    context,
                    Trait.TRAIT_REQUIREMENTS,
                    typeOf(TList.Schema).of(Trait.Schema)
                ),
                Field.create(
                    context,
                    StringDataIdProviderSchema.Fields.FIELD_REF,
                    TString.Schema.toType()
                )
            ).toTList(context)

            override val traits: TList<TraitSchema> = SimpleTList(emptyList())

            override val schema get() = CompositeSchema.Schema

            @Suppress("UNCHECKED_CAST")
            override fun <T : Data> getField(reference: String): T =
                getOptionalField(reference)
                    ?: error("Field $reference not found on materialized StringDataIdProviderSchema")

            @Suppress("UNCHECKED_CAST")
            override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
                DataSchema.Fields.SCHEMA_ID -> this.id as T
                DataSchema.Fields.PARENT -> this.parent as T
                Trait.TRAIT_REQUIREMENTS -> this.traitRequirements as T
                StringDataIdProviderSchema.Fields.FIELD_REF -> this.fieldRef as T
                CompositeSchema.Fields.VALUES -> this.values as T
                CompositeSchema.Fields.TRAITS -> this.traits as T
                else -> null
            }
        }
    },
    lazyFields = {
        val traitValues = mutableListOf<Field>().apply {
            list(Trait.TRAIT_REQUIREMENTS, of = Trait.Schema)
            string(StringDataIdProviderSchema.Fields.FIELD_REF)
        }
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to CompositeDataIdProvider.Schema,
            StringDataIdProviderSchema.Fields.FIELD_REF to SimpleTString(fieldRef),
            CompositeSchema.Fields.VALUES to SimpleTList(traitValues),
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>()),
            Trait.TRAIT_REQUIREMENTS to SimpleTList(
                listOf(
                    GetStringDataId.Schema
                )
            )
        )
    }
), StringDataIdProviderSchema {
    override val schema get() = super<LazyComposite>.schema
    override val fieldRef get() = getField<TString>(StringDataIdProviderSchema.Fields.FIELD_REF)
    override val traitRequirements get() = getField<TList<TraitRequirementSchema>>(Trait.TRAIT_REQUIREMENTS)
}

fun operation(
    id: String,
    parent: DataSchema = OperationSchema.Schema,
    receiver: Type? = null,
    typeParams: MutableList<TypeParameterDecl>.() -> Unit = { },
    params: MutableList<Field>.() -> Unit = { },
    traits: List<TraitSchema> = emptyList(),
    returnType: Type = Data.Schema.toType(),
): OperationSchema {
    val traitIds = traits.map { it.id.get() }.toSet()
    if (org.tenshu.core.trait.IoOperation.ID in traitIds &&
        org.tenshu.core.trait.TerminalOperation.ID !in traitIds
    ) {
        // Inline non-terminal IoOperations leak `AsyncReference` through resolveDynamicProperty
        // and similar sync entry points. Re-allow once those sites learn to drive AsyncReference.
        error("Operation '$id' declares IoOperation but not TerminalOperation. Only terminal operations may currently be IoOperations.")
    }
    return object : LazyComposite<OperationSchema>(
    materializeFunc = { context ->
        val p = mutableListOf<Field>().apply(params)
        val tp = mutableListOf<TypeParameterDecl>().apply(typeParams)
        val t = if (parent is CompositeSchema) parent.traits.toList() + traits else traits
        val paramsList = context.dataFactory().createList<Field>(p)
        val typeParamsList = context.dataFactory().createList<TypeParameterDecl>(tp)
        context.create(
            type = TraitRequirementSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent,
                paramsList, // values
                context.dataFactory().createList<TraitSchema>(t), // traits
                paramsList, // params
                returnType,
                receiver, // 6: nullable
                typeParamsList // 7: always present (possibly empty)
            )
        )
    },
    lazyFields = {
        val mergedParams = SimpleTList(mutableListOf<Field>().apply(params))
        val tpList = SimpleTList(mutableListOf<TypeParameterDecl>().apply(typeParams))
        val parentTraits =
            if (parent is CompositeSchema) parent.getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
                .toList() else emptyList()
        val mergedTraits = parentTraits + traits
        buildMap {
            put(DataSchema.Fields.SCHEMA_ID, SimpleTString(id))
            put(DataSchema.Fields.PARENT, parent)
            put(OperationSchema.Fields.PARAMS, mergedParams)
            put(CompositeSchema.Fields.TRAITS, SimpleTList(mergedTraits))
            put(OperationSchema.Fields.RETURN_TYPE, returnType)
            put(CompositeSchema.Fields.VALUES, mergedParams)
            put(OperationSchema.Fields.TYPE_PARAMS, tpList)
            if (receiver != null) put(OperationSchema.Fields.RECEIVER, receiver)
        }
    }
), OperationSchema {
        override val schema get() = super<LazyComposite>.schema
        override val params get() = getField<TList<Field>>(OperationSchema.Fields.PARAMS)
        override val returnType get() = getField<Type>(OperationSchema.Fields.RETURN_TYPE)
        override val receiver get() = getOptionalField<Type>(OperationSchema.Fields.RECEIVER)
        override val typeParams get() = getField<TList<TypeParameterDecl>>(OperationSchema.Fields.TYPE_PARAMS)
    }
}

fun traitRequirementSchema(
    id: String,
    parent: DataSchema = OperationSchema.Schema,
    receiver: Type? = null,
    typeParams: MutableList<TypeParameterDecl>.() -> Unit = { },
    params: MutableList<Field>.() -> Unit = { },
    traits: List<TraitSchema> = emptyList(),
    returnType: Type = Data.Schema.toType(),
): TraitRequirementSchema = object : LazyComposite<TraitRequirementSchema>(
    materializeFunc = { context ->
        val p = mutableListOf<Field>().apply(params)
        val tp = mutableListOf<TypeParameterDecl>().apply(typeParams)
        val t = if (parent is CompositeSchema) parent.traits.toList() + traits else traits
        val paramsList = context.dataFactory().createList<Field>(p)
        val typeParamsList = context.dataFactory().createList<TypeParameterDecl>(tp)
        context.create(
            type = TraitRequirementSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent,
                paramsList, // values
                context.dataFactory().createList<TraitSchema>(t), // traits
                paramsList, // params
                returnType,
                receiver, // 6: nullable
                typeParamsList // 7: always present (possibly empty)
            )
        ) as TraitRequirementSchema
    },
    lazyFields = {
        val mergedParams = SimpleTList(mutableListOf<Field>().apply(params))
        val tpList = SimpleTList(mutableListOf<TypeParameterDecl>().apply(typeParams))
        val parentTraits =
            if (parent is CompositeSchema) parent.getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
                .toList() else emptyList()
        val mergedTraits = parentTraits + traits
        buildMap {
            put(DataSchema.Fields.SCHEMA_ID, SimpleTString(id))
            put(DataSchema.Fields.PARENT, parent)
            put(OperationSchema.Fields.PARAMS, mergedParams)
            put(CompositeSchema.Fields.TRAITS, SimpleTList(mergedTraits))
            put(OperationSchema.Fields.RETURN_TYPE, returnType)
            put(CompositeSchema.Fields.VALUES, mergedParams)
            put(OperationSchema.Fields.TYPE_PARAMS, tpList)
            if (receiver != null) put(OperationSchema.Fields.RECEIVER, receiver)
        }
    }
), TraitRequirementSchema {
    override val schema get() = super<LazyComposite>.schema
    override val params get() = getField<TList<Field>>(OperationSchema.Fields.PARAMS)
    override val returnType get() = getField<Type>(OperationSchema.Fields.RETURN_TYPE)
    override val receiver get() = getOptionalField<Type>(OperationSchema.Fields.RECEIVER)
    override val typeParams get() = getField<TList<TypeParameterDecl>>(OperationSchema.Fields.TYPE_PARAMS)
}

fun valueTraitRequirementSchema(
    id: String,
    parent: DataSchema = TraitRequirementSchema.Schema,
    receiver: Type? = null,
    typeParams: MutableList<TypeParameterDecl>.() -> Unit = { },
    params: MutableList<Field>.() -> Unit = { },
    traits: List<TraitSchema> = emptyList(),
    returnType: Type = Data.Schema.toType(),
): ValueTraitRequirementSchema = object : LazyComposite<ValueTraitRequirementSchema>(
    materializeFunc = { context ->
        val p = mutableListOf<Field>().apply(params)
        val tp = mutableListOf<TypeParameterDecl>().apply(typeParams)
        val t = if (parent is CompositeSchema) parent.traits.toList() + traits else traits
        val paramsList = context.dataFactory().createList<Field>(p)
        val typeParamsList = context.dataFactory().createList<TypeParameterDecl>(tp)
        context.create(
            type = ValueTraitRequirementSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent,
                paramsList, // values
                context.dataFactory().createList<TraitSchema>(t), // traits
                paramsList, // params
                returnType,
                receiver, // 6: nullable
                typeParamsList // 7: always present (possibly empty)
            )
        ) as ValueTraitRequirementSchema
    },
    lazyFields = {
        val mergedParams = SimpleTList(mutableListOf<Field>().apply(params))
        val tpList = SimpleTList(mutableListOf<TypeParameterDecl>().apply(typeParams))
        val parentTraits =
            if (parent is CompositeSchema) parent.getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
                .toList() else emptyList()
        val mergedTraits = parentTraits + traits
        buildMap {
            put(DataSchema.Fields.SCHEMA_ID, SimpleTString(id))
            put(DataSchema.Fields.PARENT, parent)
            put(OperationSchema.Fields.PARAMS, mergedParams)
            put(CompositeSchema.Fields.TRAITS, SimpleTList(mergedTraits))
            put(OperationSchema.Fields.RETURN_TYPE, returnType)
            put(CompositeSchema.Fields.VALUES, mergedParams)
            put(OperationSchema.Fields.TYPE_PARAMS, tpList)
            if (receiver != null) put(OperationSchema.Fields.RECEIVER, receiver)
        }
    }
), ValueTraitRequirementSchema {
    override val schema get() = super<LazyComposite>.schema
    override val params get() = getField<TList<Field>>(OperationSchema.Fields.PARAMS)
    override val returnType get() = getField<Type>(OperationSchema.Fields.RETURN_TYPE)
    override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
    override val receiver get() = getOptionalField<Type>(OperationSchema.Fields.RECEIVER)
    override val typeParams get() = getField<TList<TypeParameterDecl>>(OperationSchema.Fields.TYPE_PARAMS)
}

fun composite(
    id: String,
    parent: DataSchema? = null,
    typeParams: MutableList<TypeParameterDecl>.() -> Unit = { },
    traits: List<TraitSchema> = emptyList(),
    fields: MutableList<Field>.() -> Unit = { },
): CompositeSchema {
    val getParent = { parent ?: if (id != Data.ID) Data.Schema else null }
    return object : LazyComposite<CompositeSchema>(
        materializeFunc = { context ->
            val p = getParent()
            val traitsList = if (p is CompositeSchema) p.traits.toList() + traits else traits
            val fieldsList = if (p is CompositeSchema) {
                p.values.toList().toMutableList().apply(fields)
            } else {
                mutableListOf<Field>().apply(fields)
            }
            val tpList = mutableListOf<TypeParameterDecl>().apply(typeParams)
            context.create(
                type = CompositeSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    p ?: DataSchema.Schema,
                    context.dataFactory().createList<Field>(fieldsList),
                    context.dataFactory().createList<TraitSchema>(traitsList),
                    context.dataFactory().createList<TypeParameterDecl>(tpList)
                )
            )
        },
        lazyFields = {
            val p = getParent()
            val parentTraits =
                if (p is CompositeSchema) p.getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
                    .toList() else emptyList()
            val mergedTraits = parentTraits + traits
            val mergedValues = if (p is CompositeSchema) {
                p.values.toList().toMutableList().apply(fields)
            } else {
                mutableListOf<Field>().apply(fields)
            }
            val mergedTypeParams = SimpleTList(
                mutableListOf<TypeParameterDecl>().apply(typeParams)
            )
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to p,
                CompositeSchema.Fields.TRAITS to SimpleTList(mergedTraits),
                CompositeSchema.Fields.VALUES to SimpleTList(mergedValues),
                CompositeSchema.Fields.TYPE_PARAMS to mergedTypeParams
            )
        }
    ), CompositeSchema {
        override val schema get() = super<LazyComposite>.schema
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
        override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        override val typeParams get() = getField<TList<TypeParameterDecl>>(CompositeSchema.Fields.TYPE_PARAMS)
    }
}

fun value(
    id: String,
    parent: DataSchema,
): ValueSchema = object : LazyComposite<ValueSchema>(
    materializeFunc = { context ->
        context.create(
            type = ValueSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent,
                null // field was removed from interface but remains in schema for now
            )
        )
    },
    lazyFields = {
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to parent,
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>())
        )
    }
), ValueSchema {
    override val schema get() = super<LazyComposite>.schema
}

fun service(
    id: String,
    relations: List<RelationSchema> = emptyList(),
    /** Schema version, never serialized — see [ServiceSchema.version]. */
    version: Int = 1
): ServiceSchema {
    val declaredVersion = version
    return object : LazyComposite<ServiceSchema>(
    materializeFunc = { context ->
        context.create(
            type = ServiceSchema.ID,
            fields = listOf(
                id.toTString(context),
                ServiceSchema.Schema,
                context.dataFactory().createList<RelationSchema>(relations)
            )
        )
    },
    lazyFields = {
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to ServiceSchema.Schema,
            ServiceSchema.Fields.RELATIONS to SimpleTList(relations),
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>())
        )
    }
    ), ServiceSchema {
        override val schema get() = super<LazyComposite>.schema
        override val relationSchemas get() = getField<TList<RelationSchema>>(ServiceSchema.Fields.RELATIONS)
        override val version get() = declaredVersion
    }
}

fun constant(
    id: String,
    parent: DataSchema? = null
): ConstantSchema = object : LazyComposite<ConstantSchema>(
    materializeFunc = { context ->
        context.create(
            type = ConstantSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent ?: DataSchema.Schema
            )
        )
    },
    lazyFields = {
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to (parent ?: TConstant.Schema),
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>())
        )
    }
), ConstantSchema {
    override val schema get() = super<LazyComposite>.schema
}

fun reference(id: String): ReferenceSchema = object : LazyComposite<ReferenceSchema>(
    materializeFunc = { context ->
        context.create(
            type = ReferenceSchema.ID,
            fields = listOf(
                id.toTString(context),
                ReferenceSchema.Schema
            )
        )
    },
    lazyFields = {
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to ReferenceSchema.Schema,
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>())
        )
    }
), ReferenceSchema {
    override val schema get() = super<LazyComposite>.schema
}

/**
 * Declares a [TypeParameterDecl] inside an `operation(...)`'s `typeParams` block. Returns
 * the binder so it can be captured and reused later in the same builder via the
 * binder-based [listType] / [maybeType] helpers.
 */
fun MutableList<TypeParameterDecl>.type(
    name: String,
    bound: DataSchema = Data.Schema,
): TypeParameterDecl {
    val decl = TypeParameterDecl.create(name = name, bound = bound)
    add(decl)
    return decl
}

/** Returns a [TypeVariable] reference by name. */
fun typeVar(name: String): Type = TypeVariable.create(name = name)

/** Creates a list type whose element references a declared type variable by name. */
fun listType(elementVariable: String): Type =
    typeOf(TList.Schema).of(typeVar(elementVariable))

/** Creates a list type whose element references the given [TypeParameterDecl]. */
fun listType(element: TypeParameterDecl): Type =
    listType(element.name.get())

/** Creates a Maybe type whose element references a declared type variable by name. */
fun maybeType(elementVariable: String): Type =
    typeOf(composite(Maybe.ID)).of(typeVar(elementVariable), variance = Variance.Covariant)

/** Creates a Maybe type whose element references the given [TypeParameterDecl]. */
fun maybeType(element: TypeParameterDecl): Type =
    maybeType(element.name.get())

/** Converts a [DataSchema] to a basic [Type]. */
fun DataSchema.toType(): Type = Type.create(baseSchema = this)

/** Returns a [Type] builder for the given schema. */
fun typeOf(schema: DataSchema): Type = schema.toType()

/** Parameterizes a [Type] with the given [params]. */
fun Type.of(vararg params: Type, variance: Variance = Variance.Invariant): Type =
    of(*params.map { TypeParameter.create(type = it, variance = variance) }.toTypedArray())

/** Parameterizes a [Type] with the given [params] (shorthand for schemas). */
fun Type.of(vararg params: DataSchema, variance: Variance = Variance.Invariant): Type =
    of(*params.map { TypeParameter.create(type = it.toType(), variance = variance) }.toTypedArray())

/** Parameterizes a [Type] with the given [parameters]. */
fun Type.of(vararg parameters: TypeParameter): Type = Type.create(
    baseSchema = (this as GenericType).baseSchema,
    params = parameters.toList()
)

/** Creates a list type for the given [itemType]. */
fun listType(itemType: DataSchema = Data.Schema): Type =
    typeOf(TList.Schema).of(itemType)

/** Creates a list type for the given [itemType]. */
fun listType(itemType: Type): Type =
    typeOf(TList.Schema).of(itemType)

/** Returns a [Type] for [TList] parameterized with [itemType]. */
fun TList.Companion.typeOf(itemType: DataSchema): Type = listType(itemType)

/** Returns a [Type] for [TList] parameterized with [itemType]. */
fun TList.Companion.typeOf(itemType: Type): Type = listType(itemType)

/** Creates a Maybe type for the given [itemType]. */
fun maybeType(itemType: DataSchema = Data.Schema): Type =
    typeOf(composite(Maybe.ID)).of(itemType, variance = Variance.Covariant)

/** Creates a Maybe type for the given [itemType]. */
fun maybeType(itemType: Type): Type =
    typeOf(composite(Maybe.ID)).of(itemType, variance = Variance.Covariant)

/** Returns a [Type] for [Maybe] parameterized with [itemType]. */
fun Maybe.Companion.typeOf(itemType: DataSchema): Type = maybeType(itemType)

/** Returns a [Type] for [Maybe] parameterized with [itemType]. */
fun Maybe.Companion.typeOf(itemType: Type): Type = maybeType(itemType)

fun Type.toParameter(variance: Variance = Variance.Invariant): TypeParameter =
    TypeParameter.create(type = this, variance = variance)

fun unionType(vararg types: Type): UnionType = UnionType.create(types = types.toList())

fun unionType(vararg schemas: DataSchema): UnionType =
    UnionType.create(types = schemas.map { it.toType() })

fun unionSchema(
    id: String,
    parent: DataSchema? = null,
    members: () -> List<DataSchema>
): UnionSchema = object : LazyComposite<UnionSchema>(
    materializeFunc = { context ->
        val m = members()
        context.create(
            type = UnionSchema.ID,
            fields = listOf(
                id.toTString(context),
                parent ?: CompositeSchema.Schema,
                context.dataFactory().createList<Field>(emptyList()), // values
                context.dataFactory().createList<TraitSchema>(emptyList()), // traits
                context.dataFactory().createList<DataSchema>(m) // members
            )
        )
    },
    lazyFields = {
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to (parent ?: CompositeSchema.Schema),
            UnionSchema.Fields.MEMBERS to SimpleTList(members()),
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>()),
            CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>())
        )
    }
), UnionSchema {
    override val schema get() = super<LazyComposite>.schema
    override val members get() = getField<TList<DataSchema>>(UnionSchema.Fields.MEMBERS)
    override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
    override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
}

fun valueTraitRequirement(id: String): ValueTraitRequirementSchema =
    object : LazyComposite<ValueTraitRequirementSchema>(
        materializeFunc = { context ->
            context.create(
                type = ValueTraitRequirementSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    ValueTraitRequirement.Schema,
                    context.dataFactory().createList<Field>(emptyList()), // values
                    context.dataFactory().createList<TraitSchema>(emptyList()), // traits
                    context.dataFactory().createList<Field>(emptyList()), // params
                    Type.create(baseSchema = Data.Schema), // returnType
                    null, // 6: receiver
                    context.dataFactory()
                        .createList<TypeParameterDecl>(emptyList()) // 7: typeParams
                )
            ) as ValueTraitRequirementSchema
        },
        lazyFields = {
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to ValueTraitRequirement.Schema,
                OperationSchema.Fields.PARAMS to SimpleTList(emptyList<Field>()),
                OperationSchema.Fields.RETURN_TYPE to Type.create(baseSchema = Data.Schema),
                OperationSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>()),
                CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
                CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>())
            )
        }
    ), ValueTraitRequirementSchema {
        override val schema get() = super<LazyComposite>.schema
        override val params get() = getField<TList<Field>>(OperationSchema.Fields.PARAMS)
        override val returnType get() = getField<Type>(OperationSchema.Fields.RETURN_TYPE)
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
        override val receiver get() = getOptionalField<Type>(OperationSchema.Fields.RECEIVER)
        override val typeParams get() = getField<TList<TypeParameterDecl>>(OperationSchema.Fields.TYPE_PARAMS)
    }

@Suppress("UNCHECKED_CAST")
fun <T : TraitSchema> trait(
    id: String,
    parent: DataSchema? = Data.Schema,
    traitRequirements: List<Data> = emptyList(),
    fields: MutableList<Field>.() -> Unit = { },
): T = object : LazyComposite<T>(
    materializeFunc = { context ->
        object : TraitSchema, DataIdProviderSchema, CompositeDataIdProviderSchema {
            override val id: TString = id.toTString(context)
            override val parent: DataSchema? = parent
            override val traitRequirements =
                traitRequirements.toTList(context) as TList<TraitRequirementSchema>

            override val values: TList<Field> = listOf(
                Field.create(
                    context,
                    Trait.TRAIT_REQUIREMENTS,
                    typeOf(TList.Schema).of(Trait.Schema)
                )
            ).toTList(context)

            override val traits: TList<TraitSchema> = SimpleTList(emptyList())

            override val schema get() = CompositeSchema.Schema

            @Suppress("UNCHECKED_CAST")
            override fun <T : Data> getField(reference: String): T =
                getOptionalField(reference)
                    ?: error("Field $reference not found on materialized TraitSchema")

            @Suppress("UNCHECKED_CAST")
            override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
                DataSchema.Fields.SCHEMA_ID -> this.id as T
                DataSchema.Fields.PARENT -> this.parent as? T
                Trait.TRAIT_REQUIREMENTS -> this.traitRequirements as T
                CompositeSchema.Fields.VALUES -> this.values as T
                CompositeSchema.Fields.TRAITS -> this.traits as T
                else -> null
            }
        } as T
    },
    lazyFields = {
        val mergedValues = mutableListOf<Field>().apply {
            list(Trait.TRAIT_REQUIREMENTS, of = Trait.Schema)
            fields()
        }
        mapOf(
            DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
            DataSchema.Fields.PARENT to parent,
            CompositeSchema.Fields.VALUES to SimpleTList(mergedValues),
            CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
            CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>()),
            Trait.TRAIT_REQUIREMENTS to SimpleTList(traitRequirements)
        )
    }
), TraitSchema, DataIdProviderSchema, CompositeDataIdProviderSchema {
    override val schema get() = super<LazyComposite>.schema
    override val traitRequirements get() = getField<TList<TraitRequirementSchema>>(Trait.TRAIT_REQUIREMENTS)
    override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
} as T

fun propertyRelation(
    id: String,
    fields: MutableList<Field>.() -> Unit = { },
): RelationSchema.PropertyRelationSchema =
    object : LazyComposite<RelationSchema.PropertyRelationSchema>(
        materializeFunc = { context ->
            val f = mutableListOf<Field>().apply(fields)
            context.create(
                type = RelationSchema.PropertyRelationSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    RelationSchema.Schema,
                    context.dataFactory().createList<Field>(f),
                    context.dataFactory().createList<TraitSchema>(emptyList())
                )
            )
        },
        lazyFields = {
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to (if (id == RelationSchema.ID) CompositeSchema.Schema else RelationSchema.Schema),
                CompositeSchema.Fields.VALUES to SimpleTList(
                    mutableListOf<Field>().apply(
                        fields
                    )
                ),
                CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
                CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>())
            )
        }
    ), RelationSchema.PropertyRelationSchema {
        override val schema get() = super<LazyComposite>.schema
        override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
    }

fun dynamicPropertyRelation(
    id: String,
    params: List<DataSchema>? = null,
    receiver: CompositeSchema? = null,
    callstack: ChainTemplate<*, *>,
    traits: List<TraitSchema> = emptyList(),
    deferred: Boolean = false,
): RelationSchema.DynamicPropertyRelationSchema =
    object : LazyComposite<RelationSchema.DynamicPropertyRelationSchema>(
        materializeFunc = { context ->
            val materialisedChain: TList<Data>? =
                if (params == null && receiver != null) {
                    val builtCallstack = buildCallstackFromTemplate(context, receiver, callstack)
                    context.dataFactory().createList<Data>(builtCallstack)
                } else null
            context.create(
                type = RelationSchema.DynamicPropertyRelationSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    RelationSchema.PropertyRelationSchema.Schema,
                    context.dataFactory().createList<Field>(emptyList()), // values
                    context.dataFactory().createList<TraitSchema>(traits), // traits
                    null, // typeParams
                    params?.let { context.dataFactory().createList<DataSchema>(it) }, // params
                    materialisedChain // chain
                )
            )
        },
        lazyFields = {
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to RelationSchema.PropertyRelationSchema.Schema,
                RelationSchema.DynamicPropertyRelationSchema.Fields.PARAMS to params?.let {
                    SimpleTList(
                        it
                    )
                },
                CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>()),
                CompositeSchema.Fields.TRAITS to SimpleTList(traits),
                CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>())
            )
        }
    ), RelationSchema.DynamicPropertyRelationSchema {
        override val schema get() = super<LazyComposite>.schema
        override val params get() = getOptionalField<TList<DataSchema>>(RelationSchema.DynamicPropertyRelationSchema.Fields.PARAMS)
        override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
        override val callstack get() = callstack
        override val deferredByDefault get() = deferred
    }

/**
 * Materialises a [ChainTemplate] against a fresh receiver RO at schema-construction time.
 * Used by [dynamicPropertyRelation] when the relation declares no runtime [params] AND a
 * `receiver` schema — in those cases the chain is param-independent and can be captured
 * once per `(LazyDataCreator, registry)` cache miss instead of on every resolve.
 *
 * `core-relation-system.md` Phase 4 (chain-on-schema). Param-bearing relations
 * (`File.Relation.Preview(w, h)` style) skip this path because their chain bakes runtime
 * values; `CallstackMaterializer` invokes the template at resolve time for them.
 */
@Suppress("UNCHECKED_CAST")
private fun buildCallstackFromTemplate(
    context: org.tenshu.core.core.TContext,
    receiver: CompositeSchema,
    template: ChainTemplate<*, *>,
): List<Data> {
    val initialChain: RequestChain = SimpleRequestChain(context)
    val ro = context.roProvider().createRequestObject<Data>(
        type = receiver.toType(),
        requestChain = initialChain,
        context = context,
    )
    val typedTemplate = template as ChainTemplate<Data, Data>
    val result = typedTemplate.block.invoke(ro, emptyList())
    return result.extractRequestChain()?.getCallstack() ?: emptyList()
}

private fun Data.extractRequestChain(): RequestChain? {
    if (this is RequestObject) return this.requestChain
    if (this is TResult.Success<*>) {
        val value = this.value
        if (value is Data) return value.extractRequestChain()
    }
    return null
}

fun serviceRelation(id: String): RelationSchema.ServiceRelationSchema =
    object : LazyComposite<RelationSchema.ServiceRelationSchema>(
        materializeFunc = { context ->
            context.create(
                type = RelationSchema.ServiceRelationSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    RelationSchema.Schema,
                    context.dataFactory().createList<Field>(emptyList()),
                    context.dataFactory().createList<TraitSchema>(emptyList())
                )
            )
        },
        lazyFields = {
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to RelationSchema.Schema,
                CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>()),
                CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
                CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>())
            )
        }
    ), RelationSchema.ServiceRelationSchema {
        override val schema get() = super<LazyComposite>.schema
        override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
    }

/**
 * Declares one of a service's relations — its id, its element type, and (since
 * `core-relation-system.md` §9.2.1) the two policies the proxy applies to it.
 * ⚠ R2b deleted the third, `order`: every relation now converges probe-first.
 *
 * [maintained] and [chainReadable] are **code-side schema metadata, never
 * serialized** — the [ServiceSchema.version] /
 * [RelationSchema.DynamicPropertyRelationSchema.deferredByDefault] precedent, and
 * for their reason: positional composites make an added field peer-breaking. A
 * schema materialized from bytes reports the defaults; only the DSL-declared
 * instance carries the real values, and only the proxy's own registration reads
 * them.
 *
 * @param maintained `true` = this relation's membership is *derived*, kept in
 *   step with the element rows by the generic maintainer
 *   (`ModuleConfiguration.service(...)` derives everything else from `id` +
 *   [type]). `false` = a hand-written observer owns it, which after R2 means it
 *   is not membership at all but a rollup.
 * @param chainReadable `false` when a dedicated operation is the read surface and
 *   no chain reference should be registered at all.
 */
fun serviceDataRelation(
    id: String,
    type: Type,
    maintained: Boolean = false,
    chainReadable: Boolean = true,
): RelationSchema.ServiceDataRelationSchema {
    val declaredMaintained = maintained
    val declaredChainReadable = chainReadable
    return serviceDataRelationSchema(id, type, declaredMaintained, declaredChainReadable)
}

private fun serviceDataRelationSchema(
    id: String,
    type: Type,
    declaredMaintained: Boolean,
    declaredChainReadable: Boolean,
): RelationSchema.ServiceDataRelationSchema =
    object : LazyComposite<RelationSchema.ServiceDataRelationSchema>(
        materializeFunc = { context ->
            context.create(
                type = RelationSchema.ServiceDataRelationSchema.ID,
                fields = listOf(
                    id.toTString(context),
                    RelationSchema.Schema,
                    context.dataFactory().createList<Field>(emptyList()),
                    context.dataFactory().createList<TraitSchema>(emptyList()),
                    type
                )
            )
        },
        lazyFields = {
            mapOf(
                DataSchema.Fields.SCHEMA_ID to SimpleTString(id),
                DataSchema.Fields.PARENT to RelationSchema.Schema,
                RelationSchema.ServiceDataRelationSchema.Fields.TYPE to type,
                CompositeSchema.Fields.VALUES to SimpleTList(emptyList<Field>()),
                CompositeSchema.Fields.TRAITS to SimpleTList(emptyList<TraitSchema>()),
                CompositeSchema.Fields.TYPE_PARAMS to SimpleTList(emptyList<TypeParameterDecl>())
            )
        }
    ), RelationSchema.ServiceDataRelationSchema {
        override val schema get() = super<LazyComposite>.schema
        override val type get() = getField<Type>(RelationSchema.ServiceDataRelationSchema.Fields.TYPE)
        override val values get() = getField<TList<Field>>(CompositeSchema.Fields.VALUES)
        override val traits get() = getField<TList<TraitSchema>>(CompositeSchema.Fields.TRAITS)
        override val maintained get() = declaredMaintained
        override val chainReadable get() = declaredChainReadable
    }


fun MutableList<Field>.field(
    id: String,
    type: Type,
    isOptional: Boolean = false
) {
    add(
        Field.create(
            id = id,
            type = type,
            isOptional = isOptional
        )
    )
}

fun MutableList<Field>.composite(
    id: String,
    type: DataSchema = Data.Schema,
    isOptional: Boolean = false
) {
    field(id, type.toType(), isOptional)
}

fun MutableList<Field>.int(
    id: String,
    type: ValueSchema = TInteger.Schema,
    isOptional: Boolean = false,
) {
    field(id, type.toType(), isOptional)
}

fun MutableList<Field>.timestamp(
    id: String,
    isOptional: Boolean = false,
) {
    field(id, UnixInstant.Schema.toType(), isOptional)
}

fun MutableList<Field>.string(
    id: String,
    type: ValueSchema = TString.Schema,
    isOptional: Boolean = false,
) {
    field(id, type.toType(), isOptional)
}

fun MutableList<Field>.byteArray(
    id: String,
    isOptional: Boolean = false,
) {
    field(id, TByteArray.Schema.toType(), isOptional)
}

fun MutableList<Field>.boolean(
    id: String,
    isOptional: Boolean = false,
) {
    field(id, TBoolean.Schema.toType(), isOptional)
}

/**
 * A **generic value** field — one whose concrete runtime `Data` type isn't known
 * statically (a primitive, a composite, ... chosen at write time). Stored
 * self-describingly as `ObjectStore.DbValue.GenericValue`. See [GenericData].
 */
fun MutableList<Field>.genericData(
    id: String,
    isOptional: Boolean = false,
) {
    field(id, GenericData.Schema.toType(), isOptional)
}

fun MutableList<Field>.list(
    id: String,
    of: Type,
    isOptional: Boolean = false,
) {
    field(
        id = id,
        type = of,
        isOptional = isOptional
    )
}

fun MutableList<Field>.list(
    id: String,
    of: DataSchema = Data.Schema,
    isOptional: Boolean = false,
    itemVariance: Variance = Variance.Invariant
) {
    field(
        id = id,
        type = TList.typeOf(of).of(
            of,
            variance = itemVariance
        ),
        isOptional = isOptional
    )
}

fun MutableList<Field>.maybe(
    id: String,
    of: Type,
    isOptional: Boolean = false,
) {
    field(id = id, type = of, isOptional = isOptional)
}

fun MutableList<Field>.maybe(
    id: String,
    of: DataSchema = Data.Schema,
    isOptional: Boolean = false,
) {
    field(id = id, type = Maybe.typeOf(of), isOptional = isOptional)
}

fun MutableList<Field>.map(
    id: String,
    keyType: DataSchema,
    keyTypeVariance: Variance = Variance.Invariant,
    valueType: DataSchema,
    valueTypeVariance: Variance = Variance.Invariant,
    isOptional: Boolean = false
) {
    field(
        id = id,
        type = typeOf(TMap.Schema).of(
            keyType.toType().toParameter(keyTypeVariance),
            valueType.toType().toParameter(valueTypeVariance)
        ),
        isOptional = isOptional
    )
}

fun MutableList<Field>.reference(
    id: String,
    type: DataSchema = Data.Schema,
    isOptional: Boolean = false,
) {
    field(id, typeOf(Reference.Schema).of(type), isOptional)
}
