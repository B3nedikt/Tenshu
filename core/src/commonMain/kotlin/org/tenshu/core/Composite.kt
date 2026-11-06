package org.tenshu.core


import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.composite

interface Composite : TArray {

    override val schema: CompositeSchema
        get() = Schema

    override fun <T : Data> getField(reference: String): T

    override fun <T : Data> getOptionalField(reference: String): T?

    companion object {
        const val ID = "tenshu/core/composite"

        val Schema: CompositeSchema = composite(
            id = ID
        )
    }
}

val Composite.fields: Map<String, Data>
    get() {
        @Suppress("UNCHECKED_CAST")
        return schema.values.toList().associate<Field, String, Data?> { field ->
            val fieldId = field.id.get()
            if (field.isOptional.get()) {
                fieldId to getOptionalField(fieldId)
            } else {
                fieldId to getField(fieldId)
            }
        }.filterValues { it != null } as Map<String, Data>
    }

/**
 * A simple, stateless implementation of [Composite] that holds its fields in a map.
 * This is used for DTOs and simple data objects that don't need platform components.
 */
open class SimpleComposite(
    override val schema: CompositeSchema,
    private val fieldValues: Map<String, Data?>
) : Composite {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getField(reference: String): T =
        fieldValues[reference] as? T
            ?: error("Field $reference not found in SimpleComposite of ${schema.id.get()}")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? = fieldValues[reference] as? T?

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Composite) return false
        if (schema.id.get() != other.schema.id.get()) return false

        val otherFields = other.fields
        if (fieldValues.size != otherFields.size) return false

        return fieldValues.all { (key, value) ->
            otherFields[key] == value
        }
    }

    override fun hashCode(): Int {
        var result = schema.id.get().hashCode()
        result = 31 * result + fieldValues.hashCode()
        return result
    }

    override fun toString(): String {
        return "${schema.id.get()}$fieldValues"
    }
}
