package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.TPair
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.collection.TMapDto
import org.tenshu.core.common.impl.collection.TPairDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference

data class EntityDto<T : Data>(
    private val fieldValues: List<Data?>,
) : Entity<T>, CompositeDto(fieldValues) {

    constructor(
        type: Data,
        fields: Map<String, Data?>,
        properties: Map<String, Data>,
    ) : this(
        listOf(
            type,
            TMapDto(
                pairs = TListDto(fields.toValuesIgnoringNull())
            ),
            TMapDto(
                pairs = TListDto(properties.toValues())
            ),
        )
    )

    override fun <T : Data> getProperty(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return properties[PropertyTypeReferenceDto(reference)] as T?
    }

    override fun <T> getFieldValue(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return fields[FieldReferenceDto(reference)] as T
    }

    // ⚠ The same lookup and the same cast; only the declared type differs, and that is the point —
    // `as T?` is legal on a miss where `as T` leaves null in a non-null slot.
    override fun <T> getOptionalFieldValue(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return fields[FieldReferenceDto(reference)] as T?
    }
}

private fun Map<String, Data?>.toValuesIgnoringNull(): List<TPair<FieldReference, Data>> {
    return mapNotNull { pair ->
        pair.value?.let {
            TPairDto(
                FieldReferenceDto(pair.key),
                it
            )
        }
    }.toList()
}

private fun Map<String, Data>.toValues(): List<TPair<PropertyTypeReference, Data>> {
    return mapNotNull { pair ->
        TPairDto(
            PropertyTypeReferenceDto(
                pair.key
            ), pair.value
        )
    }.toList()
}
