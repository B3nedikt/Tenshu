package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.SimpleTString
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory

/**
 * Declaration-site type parameter on an [OperationSchema]. Binds a name (e.g. "E") and an
 * optional bound (defaulting to [Data.Schema]) for use further down in the operation's
 * `receiver`, `params`, or `returnType` via [TypeVariable] references.
 *
 * Distinct from [TypeParameter], which is the use-site parameterisation (carries a [Type] +
 * [Variance]). [TypeParameterDecl] is the *declaration*; [TypeParameter] is the
 * *instantiation*.
 */
interface TypeParameterDecl : Composite {

    override val schema: CompositeSchema get() = Schema

    val name: TString
        get() = getField(Fields.NAME)

    val bound: DataSchema
        get() = getField(Fields.BOUND)

    companion object {
        const val ID = "tenshu/core/schema/typeParameterDecl"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.NAME)
                reference(Fields.BOUND, type = DataSchema.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            name: String,
            bound: DataSchema = Data.Schema,
        ): TypeParameterDecl = LazyTypeParameterDecl(context, name, bound)
    }

    object Fields {
        const val NAME = "${ID}/name"
        const val BOUND = "${ID}/bound"
    }
}

private class LazyTypeParameterDecl(
    private val context: TContext?,
    nameStr: String,
    override val bound: DataSchema,
) : TypeParameterDecl {

    override val name: TString = SimpleTString(nameStr)

    private val delegate: TypeParameterDecl by lazy {
        if (context == null) error("LazyTypeParameterDecl materialization requires a context.")
        context.dataFactory().createComposite(
            type = TypeParameterDecl.ID,
            fields = listOf(name, bound)
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeParameterDecl.Fields.NAME -> name as T
            TypeParameterDecl.Fields.BOUND -> bound as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeParameterDecl.Fields.NAME -> name as T
            TypeParameterDecl.Fields.BOUND -> bound as T
            else -> delegate.getOptionalField(reference)
        }
    }
}
