package org.tenshu.core.reference

import org.tenshu.core.TConstant
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.constant
import org.tenshu.core.schema.reference

interface Reference : TConstant {

    override val schema: ReferenceSchema
        get() = Schema

    fun get(): String

    companion object {
        const val ID = "tenshu/core/reference"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}

interface ReferenceSchema : ConstantSchema {

    companion object {
        const val ID = "tenshu/core/data/schema/reference"

        val Schema: ConstantSchema = constant(
            id = ID,
            parent = ConstantSchema.Schema
        )
    }
}

internal class SimpleFieldReference(private val id: String) : FieldReference {
    override fun get(): String = id
    override val schema get() = FieldReference.Schema
}

internal class SimplePropertyReference(private val id: String) : PropertyTypeReference {
    override fun get(): String = id
    override val schema get() = PropertyTypeReference.Schema
}

internal class SimpleDynamicPropertyReference(private val id: String) :
    DynamicPropertyTypeReference {
    override fun get(): String = id
    override val schema get() = DynamicPropertyTypeReference.Schema
}

internal class SimpleEntityReference(private val id: String) : EntityReference {
    override fun get(): String = id
    override val schema get() = EntityReference.Schema
}

internal class SimpleEntityListReference(private val id: String) : EntityListReference {
    override fun get(): String = id
    override val schema get() = EntityListReference.Schema
}

internal class SimpleServiceReference(private val id: String) : ServiceTypeReference {
    override fun get(): String = id
    override val schema get() = ServiceTypeReference.Schema
}
