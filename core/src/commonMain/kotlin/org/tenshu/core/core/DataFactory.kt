package org.tenshu.core.core

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.GenericType

interface DataFactory : TComponent {

    fun <T : TConstant> createConstant(type: String): T

    fun <T : TValue> createValue(type: String, value: Any): T

    fun <T : Composite> createComposite(type: String, fields: List<Data?>): T

    fun <T : Data> createList(values: List<Data?>): TList<T>

    fun <T : Composite> createEntity(
        type: String,
        fields: Map<String, Data>,
        relations: Map<String, Data>
    ): Entity<T>

    companion object {
        const val COMPONENT_ID = "core/DATA_FACTORY"
    }
}

fun TContext.dataFactory(): DataFactory = components[DataFactory.COMPONENT_ID] as DataFactory

/**
 * Low-level creation method using already converted [Data] fields.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Composite> TContext.create(type: String, fields: List<Data?>): T = dataFactory()
    .createComposite(type, fields) as T

/**
 * Creates a composite object. If properties are provided, an Entity is created.
 * Raw values in [fields] are automatically converted to Tenshu Data objects using the schema.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Composite> TContext.create(
    type: String,
    fields: List<Any?>,
    properties: Map<String, Data> = emptyMap()
): T {
    return if (properties.isEmpty()) {
        createDto(type, fields)
    } else {
        createEntity<T>(type, fields, properties) as T
    }
}

/**
 * Creates a DTO (Data Transfer Object) with automatic field conversion.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Composite> TContext.createDto(
    type: String,
    fields: List<Any?>
): T {
    val schema = schemaProvider().getSchema(type) as? CompositeSchema
        ?: error("Schema not found for $type")
    val convertedFields = convertFields(this, schema, fields)
    return dataFactory().createComposite(type, convertedFields) as T
}

/**
 * Creates an Entity with automatic field conversion.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Composite> TContext.createEntity(
    type: String,
    fields: List<Any?>,
    properties: Map<String, Data> = emptyMap()
): Entity<T> {
    val schema = schemaProvider().getSchema(type) as? CompositeSchema
        ?: error("Schema not found for $type")

    val fieldSchemas = collectFields(schema)
    val convertedFields = convertFields(this, schema, fields)

    val fieldMap = mutableMapOf<String, Data>()
    fieldSchemas.forEachIndexed { index, fieldSchema ->
        val value = convertedFields.getOrNull(index) ?: return@forEachIndexed
        fieldMap[fieldSchema.id.get()] = value
    }

    return dataFactory().createEntity(
        type = type,
        fields = fieldMap,
        relations = properties
    )
}

/**
 * Creates an Entity from an existing Composite, merging properties.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Composite> TContext.createEntity(
    composite: Composite,
    properties: Map<String, Data> = emptyMap()
): Entity<T> {
    val type = composite.id
    val schema = schemaProvider().getSchema(type) as? CompositeSchema
        ?: error("Schema not found for $type")

    val fieldSchemas = collectFields(schema)
    val fieldMap = mutableMapOf<String, Data>()
    fieldSchemas.forEach { fieldSchema ->
        val fieldId = fieldSchema.id.get()
        // Optional fields must be read via getOptionalField — getField throws on
        // them (CompositeDto.getField). An absent optional field is simply
        // omitted from the entity's field map. (Surfaced by the first optional
        // field on an entity-materialised type — TenshuIdentity.grantSeed, GR-3.)
        val value = if (fieldSchema.isOptional.get()) {
            composite.getOptionalField<Data>(fieldId)
        } else {
            composite.getField<Data>(fieldId)
        }
        if (value != null) fieldMap[fieldId] = value
    }

    val mergedProperties = if (composite is Entity<*>) {
        val existingProperties = composite.properties.mapKeys { it.key.get() }
        existingProperties + properties
    } else {
        properties
    }

    return dataFactory().createEntity(
        type = type,
        fields = fieldMap,
        relations = mergedProperties
    )
}

private fun convertFields(
    context: TContext,
    schema: CompositeSchema,
    fields: List<Any?>
): List<Data?> {
    val fieldSchemas = collectFields(schema)
    return fieldSchemas.mapIndexed { index, fieldSchema ->
        val value = fields.getOrNull(index) ?: return@mapIndexed null
        if (value is Data) return@mapIndexed value

        if (value is List<*>) {
            val convertedList = value.map {
                if (it is Data) it
                else error("Nested list items must be Data objects in generic create for index $index of ${schema.id.get()}")
            }
            return@mapIndexed context.dataFactory().createList<Data>(convertedList as List<Data?>)
        }

        val fieldTypeId = (fieldSchema.type as GenericType).baseSchema.id.get()
        context.dataFactory().createValue(fieldTypeId, value)
    }
}

// `schema.values` is the full merged field list (parent + own); see
// `docs/schema-dsl.md` §2 "Field Inheritance Invariant".
private fun collectFields(schema: CompositeSchema): List<Field> =
    schema.values.toList()
