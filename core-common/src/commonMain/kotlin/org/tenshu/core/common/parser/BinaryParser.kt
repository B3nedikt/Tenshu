package org.tenshu.core.common.parser

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.isGenericDataField
import org.tenshu.core.common.util.length
import org.tenshu.core.common.util.toLong
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.entity.Entity
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
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params
import org.tenshu.core.schema.substitute
import org.tenshu.core.schema.toType
import kotlin.reflect.KClass

class BinaryParser(
    @PublishedApi internal val schemaProvider: SchemaProvider,
    @PublishedApi internal val dataFactory: DataFactory
) {

    @Suppress("UNCHECKED_CAST")
    fun <T : Data> parse(data: ByteArray, expectedType: Type?): T {
        return parseData(
            data = data,
            currentPositionParam = 0,
            isTopLevel = true,
            expectedType = expectedType
        ).result as T
    }

    inline fun <reified T : Data> parse(data: ByteArray): T {
        @Suppress("UNCHECKED_CAST")
        val typeId = schemaProvider.getTypeId(T::class as KClass<Data>)

        val expectedType = schemaProvider.getSchema(typeId)
            ?.let { Type.create(baseSchema = it) }

        return parse(data, expectedType)
    }

    fun <T : Data> parseArray(data: ByteArray, expectedItemType: Type? = null): TList<T> {
        return parseArray(
            data = data,
            length = -1,
            isTopLevel = true,
            currentPosition = 0,
            expectedType = expectedItemType?.let {
                Type.create(
                    baseSchema = TList.Schema,
                    params = listOf(
                        TypeParameter.create(
                            type = it,
                            variance = Variance.Invariant
                        )
                    )
                )
            } ?: Type.create(baseSchema = TList.Schema)
        ).result as TList<T>
    }

    fun parseCallstack(
        data: ByteArray,
    ): Data {
        return parseArray(
            data = data,
            length = -1,
            currentPosition = 0,
            isTopLevel = true,
            expectedType = Type.create(
                baseSchema = TList.Schema,
                params = listOf(
                    TypeParameter.create(
                        type = ChainTemplate.ItemSchema,
                        variance = Variance.Invariant
                    )
                )
            )
        ).result
    }

    private fun DataSchema.isMarker(): Boolean {
        val myId = id.get()
        if (myId == ServiceSchema.ID ||
            myId == RelationSchema.ID ||
            myId == "tenshu/core/schema/field" ||
            myId == "tenshu/core/trait" ||
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
                mySchemaId == "tenshu/core/schema/field" ||
                mySchemaId == "tenshu/core/trait" ||
                mySchemaId == Reference.ID
    }

    private fun parseData(
        data: ByteArray,
        currentPositionParam: Int,
        isTopLevel: Boolean = false,
        expectedType: Type? = null
    ): ParsingResult {
        var currentPosition = currentPositionParam

        val expectedBaseSchema = expectedType?.baseSchema
        val expectedBaseId = expectedBaseSchema?.id?.get()

        // 1. NAKED REFERENCE: If we expect a reference, it's always naked (16 bytes).
        //    Tagged-polymorphic form (Reference.ID + target = 32 bytes) is also
        //    accepted for backward-compat with pre-Phase-2.5 wire bytes — when
        //    field types were Data-erased, Reference values were tagged. Parameterised
        //    composites that now expect naked Reference must still parse those bytes.
        if (expectedBaseSchema is ReferenceSchema) {
            if (currentPosition + 16 > data.size) error("Buffer overflow reading naked reference at $currentPosition")
            val firstBytes = data.copyOfRange(currentPosition, currentPosition + 16)
            if (firstBytes.contentEquals(idToUuid("tenshu/core/reference"))) {
                currentPosition += 16
                if (currentPosition + 16 > data.size) error("Buffer overflow reading reference target at $currentPosition")
            }
            val targetIdBytes = data.copyOfRange(currentPosition, currentPosition + 16)
            currentPosition += 16
            val targetId = schemaProvider.getTypeId(targetIdBytes)
            return ParsingResult(schemaProvider.getReference(targetId), currentPosition)
        }

        // 2. TAGLESS COLLECTIONS: TList omits tags if expected
        if (expectedBaseId == TList.ID) {
            if (currentPosition + 16 <= data.size) {
                val typeIdBytes = data.copyOfRange(currentPosition, currentPosition + 16)
                if (typeIdBytes.contentEquals(idToUuid(TList.ID))) {
                    currentPosition += 16
                }
            }
            return parseArray(data, -1, isTopLevel, currentPosition, expectedType)
        }

        // 3. TAGGED OR TAGLESS DATA
        var dataSchema: DataSchema? = null
        var isTaglessComposite = false
        var resolvedTypeIdBytes: ByteArray? = null

        if (currentPosition + 16 <= data.size) {
            val typeIdBytes = data.copyOfRange(currentPosition, currentPosition + 16)

            // Special case: Explicit Reference tag
            if (typeIdBytes.contentEquals(idToUuid("tenshu/core/reference"))) {
                currentPosition += 16
                if (currentPosition + 16 > data.size) error("Buffer overflow reading reference target at $currentPosition")
                val targetIdBytes = data.copyOfRange(currentPosition, currentPosition + 16)
                currentPosition += 16
                val targetId = schemaProvider.getTypeId(targetIdBytes)
                return ParsingResult(schemaProvider.getReference(targetId), currentPosition)
            }

            // Union dispatch: resolve to concrete member if possible
            if (expectedType is UnionType || expectedBaseSchema is UnionSchema) {
                val typeId = try {
                    schemaProvider.getTypeId(typeIdBytes)
                } catch (e: Exception) {
                    null
                }
                if (typeId != null) {
                    val foundSchema = schemaProvider.getSchema(typeId)
                    val matchingType =
                        findUnionMember(expectedType, expectedBaseSchema, typeId, foundSchema)
                    if (matchingType != null) {
                        return parseData(data, currentPosition, isTopLevel, matchingType)
                    }
                }
            }

            // Check if tag matches expected type or if tag is omitted for a concrete type
            if (expectedBaseId != null && expectedBaseId != Data.ID && expectedBaseSchema !is UnionSchema && expectedType !is UnionType) {
                if (typeIdBytes.contentEquals(idToUuid(expectedBaseId))) {
                    currentPosition += 16
                    dataSchema = expectedBaseSchema
                    resolvedTypeIdBytes = typeIdBytes
                } else {
                    val typeId = try {
                        schemaProvider.getTypeId(typeIdBytes)
                    } catch (e: Exception) {
                        null
                    }
                    val foundSchema = typeId?.let { schemaProvider.getSchema(it) }
                    if (foundSchema != null && foundSchema.isSubtypeOf(expectedBaseId)) {
                        currentPosition += 16
                        dataSchema = foundSchema
                        resolvedTypeIdBytes = typeIdBytes
                    } else {
                        // Tag omitted for concrete type
                        dataSchema = expectedBaseSchema
                        isTaglessComposite = true
                    }
                }
            }
        } else if (expectedBaseId != null && expectedBaseId != Data.ID && expectedBaseSchema !is UnionSchema && expectedType !is UnionType) {
            // Buffer too small for tag, but we have an expected type.
            dataSchema = expectedBaseSchema
            isTaglessComposite = true
        }

        // Default: Read tag and look up schema
        if (dataSchema == null) {
            if (currentPosition + 16 > data.size) {
                error("Buffer overflow reading type ID at $currentPosition. Remaining: ${data.size - currentPosition}")
            }
            val typeIdBytes = data.copyOfRange(currentPosition, currentPosition + 16)
            resolvedTypeIdBytes = typeIdBytes
            // ⚠ An unregistered tag is not a corrupt buffer — it is routinely a
            // caller naming something this build does not have, which at a
            // callstack element position means an operation (E3, §9.3). Carry the
            // position so `parseArray` can tell "the element's own type is
            // unknown" from "something nested inside it is", and let the wire
            // boundary decide what to call it; the parser itself stays generic.
            val typeId = try {
                schemaProvider.getTypeId(typeIdBytes)
            } catch (e: Exception) {
                if (e is UnknownTypeTagException) throw e
                throw UnknownTypeTagException(
                    position = currentPosition,
                    typeTagHex = typeIdBytes.toHexString(),
                    resolvedTypeId = null,
                    cause = e,
                )
            }
            dataSchema = schemaProvider.getSchema(typeId)
                ?: throw UnknownTypeTagException(
                    position = currentPosition,
                    typeTagHex = typeIdBytes.toHexString(),
                    resolvedTypeId = typeId,
                    cause = null,
                )
            currentPosition += 16
        }

        // Handle resolved schema
        val dataSchemaId = dataSchema.id.get()
        if (dataSchemaId == TList.ID) {
            return parseArray(data, -1, isTopLevel, currentPosition, expectedType)
        }

        if (dataSchema.isMarker()) {
            return ParsingResult(schemaProvider.getReference(dataSchemaId), currentPosition)
        }

        if (dataSchema is ValueSchema) {
            val length = if (dataSchema.length == -1) {
                if (currentPosition + 8 > data.size) {
                    // Potential null marker (0L) for optional field handled in parseComposite
                    error("Buffer overflow reading length at $currentPosition")
                }
                val len = data.copyOfRange(currentPosition, currentPosition + 8).toLong().toInt()
                currentPosition += 8
                len
            } else {
                dataSchema.length
            }
            if (currentPosition + length > data.size) error("Buffer overflow parsing value of type $dataSchemaId at $currentPosition. Length: $length, Size: ${data.size}")
            val nextValue = data.copyOfRange(currentPosition, currentPosition + length)
            currentPosition += length

            val result = if (dataSchema is ReferenceSchema) {
                schemaProvider.getReference(schemaProvider.getTypeId(nextValue))
            } else {
                dataFactory.createValue<TValue>(dataSchemaId, nextValue)
            }
            return ParsingResult(result, currentPosition)
        }

        if (dataSchema is CompositeSchema) {
            val typeBytes =
                if (isTaglessComposite) idToUuid(dataSchemaId) else resolvedTypeIdBytes ?: idToUuid(
                    dataSchemaId
                )
            return parseComposite(data, currentPosition, dataSchema, expectedType, typeBytes)
        }

        if (dataSchema is ConstantSchema) {
            return ParsingResult(dataFactory.createConstant(dataSchemaId), currentPosition)
        }

        return ParsingResult(schemaProvider.getReference(dataSchemaId), currentPosition)
    }

    private fun findUnionMember(
        expectedType: Type?,
        expectedBaseSchema: DataSchema?,
        typeId: String,
        dataSchema: DataSchema?
    ): Type? {
        if (expectedType is UnionType) {
            return expectedType.types.toList().find { member ->
                val memberBaseId = member.baseSchema?.id?.get() ?: return@find false
                if (memberBaseId == typeId || dataSchema?.isSubtypeOf(memberBaseId) == true) return@find true
                if (memberBaseId == Reference.ID && dataSchema?.isMarker() == true) return@find true
                false
            }
        }
        if (expectedBaseSchema is UnionSchema) {
            val matchingMember = expectedBaseSchema.members.toList().find { member ->
                val memberId = member.id.get()
                if (memberId == typeId || dataSchema?.isSubtypeOf(memberId) == true) return@find true
                if (memberId == Reference.ID && dataSchema?.isMarker() == true) return@find true
                false
            }
            if (matchingMember != null) return Type.create(
                baseSchema = matchingMember,
                params = expectedType?.params?.toList() ?: emptyList()
            )
        }
        return null
    }

    private fun parseComposite(
        data: ByteArray,
        currentPosition: Int,
        compositeSchema: CompositeSchema,
        expectedType: Type?,
        typeIdBytes: ByteArray
    ): ParsingResult {
        var currentPositionVar = currentPosition
        val parsedFields = mutableListOf<Data?>()
        val parentParams = expectedType?.params?.toList() ?: emptyList()
        val fields = compositeSchema.values.toList()

        // Phase 3.4 of `docs/adr/0005-composite-schema-typing.md`: substitute TypeVariable
        // references in field types using the composite's `typeParams` declaration zipped
        // against the requested Type's parameterisation. Replaces the prior single-var
        // string-match heuristic, which silently mis-substituted two-variable composites
        // (`TMap<K, V>`).
        val typeParamDecls = compositeSchema.typeParams.toList()
        val bindings: Map<String, Type> = if (typeParamDecls.isNotEmpty() && parentParams.isNotEmpty()) {
            typeParamDecls.zip(parentParams)
                .associate { (decl, param) -> decl.name.get() to param.type }
        } else {
            emptyMap()
        }

        fields.forEach { field ->
            // Decide which mechanism applies BEFORE running substitute. The legacy
            // heuristic looks at the field's *declared* type — composites whose
            // fields are still Data.Schema-erased need the parent's first param.
            // After substitute, a typeVar bound to Data also yields baseId="Data",
            // and we'd misfire the heuristic by overwriting it with the wrong
            // parent param (the parseEntity bug surfaced during Phase 2.5).
            val declaredBaseId = field.type.baseSchema?.id?.get()
            val isDataErased = declaredBaseId == "tenshu/core/data" ||
                    declaredBaseId == "tenshu/core/schema/typeParameter"

            // Phase 3.4 substitute: resolves TypeVariable references in field types.
            // After Phase 2.5, parameterised composites declare fields with `typeVar(...)`
            // shapes (e.g. `TPair.first: typeVar("A")`). When parent parameterisation is
            // available, substitute resolves the type vars; when it isn't (top-level
            // chain construction, type-erased reads), `defaultForUnbound = Data` falls
            // back to the historical erased wire format.
            var fieldType = substitute(
                type = field.type,
                bindings = bindings,
                defaultForUnbound = Data.Schema.toType(),
            )
            // Legacy heuristic for composites that haven't migrated to typeVar field
            // declarations. Subsumed once Phase 2.5 fully lands.
            if (isDataErased && parentParams.isNotEmpty()) {
                fieldType = parentParams.first().getField(TypeParameter.Fields.TYPE)
            }

            // A genericData field is polymorphic — parse it via the tagged path (read
            // the concrete type id from the wire), the same way a Data.Schema field is
            // read. Mirrors the serializer; see LinearSerializer.
            if (field.isGenericDataField) {
                fieldType = Data.Schema.toType()
            }

            if (field.isOptional.get()) {
                if (currentPositionVar >= data.size) {
                    parsedFields.add(null)
                    return@forEach
                }

                val optionalBaseSchema = fieldType.baseSchema
                if (optionalBaseSchema is ValueSchema) {
                    // An optional VALUE field is always length-prefixed by the
                    // serializer: `Field.length` is -1 for *any* optional field, so
                    // LinearSerializer writes an 8-byte presence/length marker ahead
                    // of the value — 0 = null, else the byte length of the value that
                    // follows — for fixed-length values (TInteger/TBoolean) just as for
                    // variable-length ones (TString/TByteArray). The natural-length read
                    // in `parseData` keys off the *schema* length (8 for an int), so it
                    // ignores that prefix and reads the value at the schema's length,
                    // desyncing every following field by 8 ("No type id registered for
                    // bytes: 00000008…"). Read the marker here, mirroring parseData's
                    // ValueSchema length==-1 path. A present value 0 / false serialises
                    // with marker 8 (not 0), so it can never be mistaken for null.
                    // See OptionalValueFieldRoundTripTest.
                    if (currentPositionVar + 8 > data.size) {
                        error("Buffer overflow reading optional value length at $currentPositionVar")
                    }
                    val len = data.copyOfRange(currentPositionVar, currentPositionVar + 8).toLong().toInt()
                    currentPositionVar += 8
                    if (len == 0) {
                        parsedFields.add(null)
                        return@forEach
                    }
                    if (currentPositionVar + len > data.size) {
                        error("Buffer overflow reading optional value at $currentPositionVar. Length: $len, Size: ${data.size}")
                    }
                    val valueBytes = data.copyOfRange(currentPositionVar, currentPositionVar + len)
                    currentPositionVar += len
                    val valueSchemaId = optionalBaseSchema.id.get()
                    val value = if (optionalBaseSchema is ReferenceSchema) {
                        schemaProvider.getReference(schemaProvider.getTypeId(valueBytes))
                    } else {
                        dataFactory.createValue<TValue>(valueSchemaId, valueBytes)
                    }
                    parsedFields.add(value)
                    return@forEach
                }

                if (currentPositionVar + 8 <= data.size) {
                    val len = data.copyOfRange(currentPositionVar, currentPositionVar + 8).toLong()
                    if (len == 0L) {
                        currentPositionVar += 8
                        parsedFields.add(null)
                        return@forEach
                    }
                }
            }

            val parsingResult = parseData(data, currentPositionVar, false, fieldType)
            currentPositionVar = parsingResult.currentPosition
            parsedFields.add(parsingResult.result)
        }

        if (compositeSchema is OperationSchema) {
            val parameters = parsedFields.mapIndexed { index, data ->
                compositeSchema.values.toList()[index].id.get() to data
            }.filter { it.second != null }.associate { it.first to it.second!! }

            return ParsingResult(
                OperationDto(params = parameters, schema = compositeSchema),
                currentPositionVar
            )
        }

        // Preserve the null sentinels at their field positions. CompositeDto maps
        // its constructor list to schema fields BY INDEX, so dropping the nulls of
        // absent optional fields (the old `filterNotNull()`) shifted every later
        // present field left — a null optional field in the MIDDLE of a composite
        // (e.g. IngestSource.lastRunAt between watchedPaths and lastStatus) then
        // mis-mapped the trailing fields. CompositeDto.fieldMap already ignores
        // nulls (mapIndexedNotNull), so positional nulls are harmless for absent
        // optionals and load-bearing for interleaved ones.
        return createComposite(typeIdBytes, parsedFields, currentPositionVar)
    }

    private fun createComposite(
        protocolTypeBytes: ByteArray,
        fields: List<Data?>,
        currentPosition: Int
    ): ParsingResult {
        val typeId = schemaProvider.getTypeId(protocolTypeBytes)
        var composite = dataFactory.createComposite<Composite>(typeId, fields)

        if (composite is Entity<*>) {
            val entityTypeId = composite.type.get()
            val fieldsMap =
                (composite.fields as Map<*, *>).entries.associate { (it.key as Reference).get() to it.value as Data }
            val propertiesMap =
                (composite.properties as Map<*, *>).entries.associate { (it.key as Reference).get() to it.value as Data }
            composite = dataFactory.createEntity<Composite>(
                type = entityTypeId,
                fields = fieldsMap,
                relations = propertiesMap
            )
        }

        return ParsingResult(composite, currentPosition)
    }

    private fun parseArray(
        data: ByteArray,
        length: Int,
        isTopLevel: Boolean = false,
        currentPosition: Int,
        expectedType: Type? = null
    ): ParsingResult {
        var currentPositionVar = currentPosition
        val resultList = mutableListOf<Data>()

        val lastIndex = if (isTopLevel.not() && length == -1) {
            val len = data.copyOfRange(currentPosition, currentPosition + 8).toLong().toInt()
            currentPositionVar += 8
            currentPositionVar + len
        } else {
            if (length == -1) data.size else currentPositionVar + length
        }

        val expectedItemType = expectedType?.params?.toList()?.firstOrNull()?.type

        while (currentPositionVar < lastIndex) {
            val elementStart = currentPositionVar
            val parsingResult = try {
                parseData(data, currentPositionVar, false, expectedItemType)
            } catch (e: UnknownTypeTagException) {
                // A top-level element of a callstack IS an operation, by
                // construction — but only when the unknown tag is the element's
                // OWN, not one nested in its parameters. Position tells them
                // apart, so this never mislabels a parameter's data type as an
                // operation (E3, `core-schema-evolution.md` §9.3).
                if (isTopLevel && e.position == elementStart) throw e.asUnsupportedOperation()
                throw e
            }
            currentPositionVar = parsingResult.currentPosition
            resultList.add(parsingResult.result)
        }
        return ParsingResult(TListDto(resultList), currentPositionVar)
    }
}

private data class ParsingResult(
    val result: Data,
    val currentPosition: Int
)
