package org.tenshu.core.native

import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.reference
import kotlin.reflect.KClass

interface AndroidType : NativeType {

    val fullyQualifiedName: String

    companion object {
        const val ID = "tenshu/native/type/android"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                // Verbatim id retained from when this borrowed `Relation.Fields.FROM`;
                // `Relation` is now a fieldless marker so the constant is gone. This
                // schema is otherwise unused (only `AndroidType.ID` is referenced).
                composite(id = "tenshu/core/relation/from")
            }
        )
    }
}

fun androidSchema(clazz: KClass<*>) = androidType(clazz).schema

fun androidType(clazz: KClass<*>): AndroidType = object : AndroidType {

    override val fullyQualifiedName: String
        get() = clazz.qualifiedName!!.replace(".", "/")

    override val schema: ReferenceSchema =
        reference(id = "${AndroidType.ID}$fullyQualifiedName")

    override fun get(): String = schema.id.get()
}
