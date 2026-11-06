package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.isConstantField
import org.tenshu.core.common.util.isGenericDataField
import org.tenshu.core.common.util.isValueField
import org.tenshu.core.common.util.length
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.fields
import org.tenshu.core.id
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.UnionSchema
import org.tenshu.core.schema.UnionType
import org.tenshu.core.schema.substitute
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params
import org.tenshu.core.trait.Trait

class LinearSerializer(
    private val schemaProvider: SchemaProvider? = null
) {

    private lateinit var valueTokenizer: (TValue) -> TokenString

    fun serialize(
        data: Data,
        valueTokenizer: (TValue) -> TokenString,
        isTopLevel: Boolean = true,
        expectedType: Type? = null
    ): TokenString {
        this.valueTokenizer = valueTokenizer
        return serialize(data, isTopLevel, expectedType, isOptimized = expectedType != null)
    }

    private fun DataSchema.isMarker(): Boolean {
        val myId = id.get()
        if (myId == ServiceSchema.ID ||
            myId == RelationSchema.ID ||
            myId == OperationSchema.ID ||
            myId == ConstantSchema.ID ||
            myId == "tenshu/core/schema/field" ||
            myId == Trait.ID ||
            myId == Reference.ID ||
            myId == "tenshu/core/reference/field" ||
            myId == "tenshu/core/reference/list/entity" ||
            myId == "tenshu/core/reference/entity" ||
            myId == "tenshu/core/reference/type/dynamicProperty" ||
            myId == "tenshu/core/reference/type/service" ||
            myId == "tenshu/core/reference/type/property"
        ) return true

        if (isSubtypeOf(ServiceSchema.ID) ||
            isSubtypeOf(RelationSchema.ID) ||
            isSubtypeOf(OperationSchema.ID) ||
            isSubtypeOf(ConstantSchema.ID) ||
            isSubtypeOf(Reference.ID) ||
            this is ReferenceSchema
        ) return true

        val mySchemaId = try {
            schema.id.get()
        } catch (e: Exception) {
            null
        }
        return mySchemaId == ServiceSchema.ID ||
                mySchemaId == RelationSchema.ID ||
                mySchemaId == OperationSchema.ID ||
                mySchemaId == ConstantSchema.ID ||
                mySchemaId == "tenshu/core/schema/field" ||
                mySchemaId == Trait.ID ||
                mySchemaId == Reference.ID
    }

    private fun serialize(
        data: Data,
        isTopLevel: Boolean = true,
        expectedType: Type? = null,
        isOptimized: Boolean = false
    ): TokenString {
        return buildTokenString {

            var currentExpectedType = expectedType
            val dataId = data.id

            // UNION DISPATCH: If expected type is a union, resolve to the concrete member type
            val expectedBaseSchema = currentExpectedType?.baseSchema
            if (currentExpectedType is UnionType || expectedBaseSchema is UnionSchema) {
                val dataSchema = data.schema
                if (currentExpectedType is UnionType) {
                    val matchingType = currentExpectedType.types.toList().find { member ->
                        val memberBaseId = member.baseSchema?.id?.get()
                        memberBaseId != null && (memberBaseId == dataId || dataSchema.isSubtypeOf(
                            memberBaseId
                        ))
                    }
                    if (matchingType != null) {
                        currentExpectedType = matchingType
                    }
                } else if (expectedBaseSchema is UnionSchema) {
                    val matchingMember = expectedBaseSchema.members.toList().find { member ->
                        val memberId = member.id.get()
                        memberId == dataId || dataSchema.isSubtypeOf(memberId)
                    }
                    if (matchingMember != null) {
                        currentExpectedType = Type.create(
                            baseSchema = matchingMember,
                            params = currentExpectedType.params.toList()
                        )
                    }
                }
            }

            if (data is Entity<*>) {
                append(Token.ReferenceToken(Entity.ID))
                serializeFields(Entity.Schema, data, isOptimized)
                return@buildTokenString
            }

            if (dataId == TList.ID) {
                val expectedBaseId = currentExpectedType?.baseSchema?.id?.get()
                if (expectedBaseId != TList.ID) {
                    append(Token.ReferenceToken(TList.ID))
                }
                serializeList(data, currentExpectedType, isTopLevel, isOptimized)
                return@buildTokenString
            }

            // We don't omit TMap.ID yet because TMap is a standard composite in legacy format
            // if (dataId == TMap.ID && expectedBaseId == TMap.ID) { ... }

            when (dataId) {
                else -> {
                    val dataSchema = data.schema

                    if (data is Reference) {
                        val targetId = data.get()
                        val targetSchema = schemaProvider?.getSchema(targetId)
                        val expectedBaseId = currentExpectedType?.baseSchema?.id?.get()

                        // RULE: Marker schemas (Metadata) are ALWAYS serialized as naked 16-byte IDs.
                        // They are unambiguous because they are never instantiated.
                        if (targetSchema?.isMarker() == true) {
                            append(Token.ReferenceToken(targetId))
                            return@buildTokenString
                        }

                        // RULE: If the context explicitly expects a Reference, we write it naked.
                        if (expectedBaseSchema is ReferenceSchema || expectedBaseId == Reference.ID || expectedBaseId == "tenshu/core/reference" || expectedBaseId == targetId) {
                            append(Token.ReferenceToken(targetId))
                            return@buildTokenString
                        }

                        // RULE: Structural references (to Composites or Operations) in polymorphic 
                        // contexts MUST be tagged with Reference.ID to distinguish them from instances.
                        append(Token.ReferenceToken(Reference.ID))
                        append(Token.ReferenceToken(targetId))
                        return@buildTokenString
                    }

                    append(Token.ReferenceToken(dataId))

                    if (dataSchema is CompositeSchema
                        && (dataSchema.hasTrailingOptional().not()
                                || (data as? Composite)?.fields?.isNotEmpty() == true)
                    ) {
                        serializeFields(
                            dataSchema,
                            data,
                            isOptimized,
                            currentExpectedType?.params?.toList() ?: emptyList()
                        )
                    }
                    if (dataSchema is ValueSchema) {
                        val value = (data as TValue)

                        append(serializeValue(value))
                    }
                }
            }
        }
    }

    private fun DataSchema.hasTrailingOptional(): Boolean {
        if (this !is CompositeSchema) return false
        val fields = values.toList()
        return fields.size == 1 && fields.first().isOptional.get()
    }

    private fun TokenString.serializeFields(
        schema: CompositeSchema,
        data: Data,
        isOptimized: Boolean = false,
        params: List<TypeParameter> = emptyList()
    ) {
        val composite = data as Composite

        // Phase 3.4 of `docs/adr/0005-composite-schema-typing.md`: substitute TypeVariable
        // references via schema.typeParams + caller params. Replaces the prior
        // single-var string-match heuristic. The legacy fallback below catches
        // composites that haven't migrated to typeParams yet.
        val typeParamDecls = schema.typeParams.toList()
        val bindings: Map<String, Type> = if (typeParamDecls.isNotEmpty() && params.isNotEmpty()) {
            typeParamDecls.zip(params)
                .associate { (decl, param) -> decl.name.get() to param.type }
        } else {
            emptyMap()
        }

        schema.values.toList().forEachIndexed { index, field ->
            val fieldId = field.id.get()
            val fieldObject = if (field.isOptional.get()) {
                composite.getOptionalField(fieldId)
            } else {
                composite.getField<Data>(fieldId)
            }

            if (field.isOptional.get() && fieldObject == null) {
                if (field.length == -1) {
                    append(Token.IntegerToken(0))
                }
                return@forEachIndexed
            }

            if (fieldObject == null) {
                error("Missing non-optional field $fieldId in ${schema.id.get()}")
            }

            // Decide which mechanism applies BEFORE running substitute. The legacy
            // heuristic looks at the field's *declared* type — composites whose
            // fields are still Data.Schema-erased need the parent's first param.
            // After substitute, a typeVar bound to Data also yields baseId="Data",
            // and misfiring the heuristic would overwrite a correctly-substituted
            // typeVar (the parseEntity bug surfaced during Phase 2.5).
            val declaredBaseId = field.type.baseSchema?.id?.get()
            val isDataErased = declaredBaseId == "tenshu/core/data" ||
                    declaredBaseId == "tenshu/core/schema/typeParameter"

            // Phase 3.4 substitute. After Phase 2.5, parameterised composites declare
            // fields with `typeVar(...)` shapes (e.g. `TPair.first: typeVar("A")`). In
            // the optimized path with parent parameterisation we resolve them to the
            // caller's concrete types; everywhere else (no bindings — top-level
            // serialisation, non-optimized writes) `defaultForUnbound = Data` falls
            // back to the historical erased wire format so the bytes match what a
            // pre-2.5 schema would have produced.
            var fieldType = substitute(
                type = field.type,
                bindings = bindings,
                defaultForUnbound = Data.Schema.toType(),
            )
            // Legacy heuristic for composites that haven't migrated to typeVar field
            // declarations yet — Subsumed once Phase 2.5 fully lands.
            if (isOptimized && isDataErased && params.isNotEmpty()) {
                fieldType = params.first().getField<Type>(TypeParameter.Fields.TYPE)
            }

            // A genericData field is polymorphic — its concrete value type isn't known
            // from the schema, so serialize the value in tagged form (exactly like a
            // Data.Schema field) so the type is recoverable on read. Without this the
            // value would be written tagless against the GenericData composite type and
            // mis-parse (drive-offline-online.md / core-preference-service.md).
            if (field.isGenericDataField) {
                fieldType = Data.Schema.toType()
            }

            val fieldBaseSchema = fieldType.baseSchema

            when {
                field.isValueField -> {
                    val serializedValue = valueTokenizer.invoke(fieldObject as TValue)
                    if (field.length == -1) {
                        append(Token.IntegerToken(serializedValue.size.toLong()))
                    }
                    append(serializedValue)
                }

                field.isConstantField -> {
                    if (fieldBaseSchema is ReferenceSchema) {
                        append(Token.ReferenceToken((fieldObject as Reference).get()))
                    } else {
                        append(Token.ReferenceToken(fieldObject.id))
                    }
                }

                fieldBaseSchema is ReferenceSchema -> {
                    append(Token.ReferenceToken((fieldObject as Reference).get()))
                }

                else -> {
                    append(
                        serialize(
                            fieldObject,
                            isTopLevel = false,
                            expectedType = fieldType,
                            isOptimized = isOptimized
                        )
                    )
                }
            }
        }
    }

    private fun TokenString.serializeValue(
        fieldObject: Data,
    ): TokenString {

        val serializedValue = valueTokenizer.invoke(fieldObject as TValue)
        if (fieldObject.schema.length == -1) {
            append(Token.IntegerToken(serializedValue.size.toLong()))
        }

        return serializedValue
    }

    private fun TokenString.serializeList(
        list: Data,
        expectedType: Type? = null,
        isTopLevel: Boolean = false,
        isOptimized: Boolean = false
    ) {
        val dataList = when (list) {
            is TList<*> -> list.toList()
            else -> emptyList<Data>()
        }
        val expectedItemType = expectedType?.params?.toList()?.firstOrNull()?.type

        val elements = buildTokenString {
            dataList.forEach { value ->
                append(
                    serialize(
                        value,
                        isTopLevel = false,
                        expectedType = expectedItemType,
                        isOptimized = isOptimized
                    )
                )
            }
        }

        if (isTopLevel.not()) {
            append(Token.IntegerToken(elements.size.toLong()))
        }
        append(elements)
    }
}
