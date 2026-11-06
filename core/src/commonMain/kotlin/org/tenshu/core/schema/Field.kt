package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.SimpleTBoolean
import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.reference.FieldReference

interface Field : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val id: FieldReference
        get() = getField(Fields.ID)

    val type: Type
        get() = getField(Fields.TYPE)

    val isOptional: TBoolean
        get() = getField(Fields.IS_OPTIONAL)

    companion object {
        const val ID = "tenshu/core/schema/field"

        val Schema = composite(
            id = ID,
            fields = {
                reference(Fields.ID, type = FieldReference.Schema)
                composite(Fields.TYPE, type = Type.Schema)
                composite(Fields.IS_OPTIONAL, type = TBoolean.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            id: String,
            type: Type,
            isOptional: Boolean = false
        ): Field = LazyField(context, id, type, isOptional)
    }

    object Fields {
        const val ID = "${Field.ID}/id"
        const val TYPE = "${Field.ID}/type"
        const val IS_OPTIONAL = "${Field.ID}/isOptional"
    }
}

private class LazyField(
    private val context: TContext?,
    private val idString: String,
    override val type: Type,
    private val isOptionalParam: Boolean
) : Field {

    override val id: FieldReference
        get() = fieldRef(idString)

    override val isOptional: TBoolean
        get() = SimpleTBoolean(isOptionalParam)

    private val delegate: Field by lazy {
        if (context == null) error("LazyField materialization requires a context.")
        context.dataFactory().createComposite(
            type = Field.ID,
            fields = listOf(
                id,
                type,
                isOptional
            )
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            Field.Fields.ID -> id as T
            Field.Fields.TYPE -> type as T
            Field.Fields.IS_OPTIONAL -> isOptional as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            Field.Fields.ID -> id as T
            Field.Fields.TYPE -> type as T
            Field.Fields.IS_OPTIONAL -> isOptional as T
            else -> delegate.getOptionalField(reference)
        }
    }
}
