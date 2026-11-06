package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory

interface TypeParameter : Composite {

    override val schema: CompositeSchema get() = Schema

    val type: Type
        get() = getField(Fields.TYPE)

    val variance: Variance
        get() = getField(Fields.VARIANCE)

    companion object {
        const val ID = "tenshu/core/schema/typeParameter"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                composite(Fields.TYPE, type = Type.Schema)
                reference(Fields.VARIANCE, type = Variance.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            type: Type,
            variance: Variance
        ): TypeParameter = LazyTypeParameter(context, type, variance)

        fun create(
            context: TContext? = null,
            type: DataSchema,
            variance: Variance
        ): TypeParameter = create(context, Type.create(context, baseSchema = type), variance)
    }

    object Fields {
        const val TYPE = "${ID}/type"
        const val VARIANCE = "${ID}/variance"
    }
}

private class LazyTypeParameter(
    private val context: TContext?,
    override val type: Type,
    override val variance: Variance
) : TypeParameter {

    private val delegate: TypeParameter by lazy {
        if (context == null) error("LazyTypeParameter materialization requires a context.")
        context.dataFactory().createComposite(
            type = TypeParameter.ID,
            fields = listOf(type, variance)
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeParameter.Fields.TYPE -> type as T
            TypeParameter.Fields.VARIANCE -> variance as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeParameter.Fields.TYPE -> type as T
            TypeParameter.Fields.VARIANCE -> variance as T
            else -> delegate.getOptionalField(reference)
        }
    }
}
