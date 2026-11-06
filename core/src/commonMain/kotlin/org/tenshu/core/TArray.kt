package org.tenshu.core

import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.composite

interface TArray : Data {

    override val schema: CompositeSchema
        get() = Schema

    fun <T : Data> getField(reference: String): T

    fun <T : Data> getOptionalField(reference: String): T?

    companion object {
        const val ID = "tenshu/core/array"

        val Schema: CompositeSchema = composite(
            id = ID
        )
    }
}

val TArray.fields: Map<String, Data>
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
