package org.tenshu.core.common.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalToLogic

abstract class CompositeDto(
    private val fieldValues: List<Data?>,
) : Composite {

    private val fieldMap: Map<String, Data> by lazy {
        val schemaValues = schema.values.toList()
        fieldValues.mapIndexedNotNull { index, data ->
            if (data == null) return@mapIndexedNotNull null
            val field = schemaValues.getOrNull(index) ?: return@mapIndexedNotNull null
            field.id.get() to data
        }.toMap()
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        val field = schema.values.toList().find { it.id.get() == reference }
            ?: return null

        if (!field.isOptional.get()) {
            error("Field $reference is mandatory in ${schema.id.get()}. Use getField() instead.")
        }

        @Suppress("UNCHECKED_CAST")
        return fieldMap[reference] as T?
    }

    override fun <T : Data> getField(reference: String): T {
        val field = schema.values.toList().find { it.id.get() == reference }
            ?: error("Field $reference not found in ${schema.id.get()}. Available fields: ${fieldMap.keys}")

        if (field.isOptional.get()) {
            error("Field $reference is optional in ${schema.id.get()}. Use getOptionalField() instead.")
        }

        @Suppress("UNCHECKED_CAST")
        return fieldMap[reference] as? T
            ?: error("Field $reference is null in ${schema.id.get()} but was marked mandatory in the schema.")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Data) return false

        return equalToLogic(this, other).get()
    }

    override fun hashCode(): Int {
        return fieldMap.hashCode()
    }
}
