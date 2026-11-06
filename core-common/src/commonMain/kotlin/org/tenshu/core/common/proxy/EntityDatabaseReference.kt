package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.CompositeTypeReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.schema.CompositeSchema

data class EntityDatabaseReference(
    val entity: Entity<*>,
    val dataId: DataId,
    val executionStack: List<Data> = emptyList()
) : Data, Entity<Data>, DatabaseReference {
    override val schema: CompositeSchema
        get() = entity.schema

    override val type: CompositeTypeReference
        get() = entity.type

    override val fields: Map<FieldReference, Data>
        get() = entity.fields

    override val properties: Map<PropertyTypeReference, Data>
        get() = entity.properties

    override fun <T : Data> getProperty(reference: String): T? {
        return entity.getProperty(reference)
    }

    override fun <T> getFieldValue(reference: String): T {
        return entity.getFieldValue(reference)
    }

    override fun <T> getOptionalFieldValue(reference: String): T? {
        return entity.getOptionalFieldValue(reference)
    }

    override fun <T : Data> getField(reference: String): T {
        return entity.getField(reference)
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        return entity.getOptionalField(reference)
    }

    override fun getOperationContext(): OperationContext {
        return EntityOperationContext(
            entityDatabaseReference = this,
            executionStack = executionStack
        )
    }
}

data class EntityOperationContext(
    val entityDatabaseReference: EntityDatabaseReference,
    override var executionStack: List<Data>
) : OperationContext

