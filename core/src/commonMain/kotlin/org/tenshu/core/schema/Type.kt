package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.SimpleTString
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory

interface Type : Composite {

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/schema/type"

        val Schema: CompositeSchema = composite(
            id = ID
        )

        fun create(
            context: TContext? = null,
            baseSchema: DataSchema,
            params: List<TypeParameter> = emptyList()
        ): GenericType = GenericType.create(context, baseSchema, params)
    }
}

val Type.baseSchema: DataSchema?
    get() = (this as? GenericType)?.baseSchema

val Type.params: TList<TypeParameter>
    get() = (this as? GenericType)?.params ?: emptyList<TypeParameter>().toTList(null)

/**
 * For a parameterised [Type] (e.g. `TList<E>`, `Maybe<E>`), returns the first type
 * parameter's base schema — i.e. the element schema. Falls back to [Data.Schema] when the
 * type isn't parameterised. Used by parameterised RO factories registered via
 * `registerParameterisedType` / `registerParameterisedComposite` to bind an element
 * schema at construction time. Phase 5.0 of `docs/adr/0009-operation-schema-typing.md`.
 */
fun Type.elementSchema(): DataSchema =
    params.toList().firstOrNull()?.type?.baseSchema ?: Data.Schema

/**
 * Substitutes [TypeVariable] references in [type] using [bindings] (variable name →
 * concrete [Type]). Walks [GenericType] recursively so nested generics like
 * `TList<TPair<K, V>>` resolve correctly. Returns [type] unchanged when no variables
 * appear.
 *
 * When a variable is unbound: if [defaultForUnbound] is supplied, it stands in for the
 * variable; otherwise the helper throws. Strict mode (no default) is right for the
 * proxy-side validator where unbound variables indicate a malformed chain. The
 * wire-layer parser/serializer pass `Data.Schema.toType()` to preserve the historical
 * type-erased wire format when no parent parameterisation is available.
 *
 * Phase 3.1 / 3.4 of `docs/adr/0005-composite-schema-typing.md`.
 */
fun substitute(
    type: Type,
    bindings: Map<String, Type>,
    defaultForUnbound: Type? = null,
): Type {
    return when (type) {
        is TypeVariable -> bindings[type.name.get()]
            ?: defaultForUnbound
            ?: error("Unbound type variable '${type.name.get()}'; bindings: ${bindings.keys}")
        is GenericType -> {
            val newParams = type.params.toList().map { tp ->
                TypeParameter.create(
                    type = substitute(tp.type, bindings, defaultForUnbound),
                    variance = tp.variance
                )
            }
            Type.create(baseSchema = type.baseSchema, params = newParams)
        }
        else -> type
    }
}

interface GenericType : Type {

    val baseSchema: DataSchema
        get() = getField(Fields.BASE_SCHEMA)

    val params: TList<TypeParameter>
        get() = getField(Fields.PARAMS)

    companion object {
        const val ID = "tenshu/core/schema/type/generic"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Type.Schema,
            fields = {
                reference(Fields.BASE_SCHEMA, type = DataSchema.Schema)
                list(Fields.PARAMS, of = TypeParameter.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            baseSchema: DataSchema,
            params: List<TypeParameter> = emptyList()
        ): GenericType = LazyGenericType(context, baseSchema, params)
    }

    object Fields {
        const val BASE_SCHEMA = "${ID}/baseSchema"
        const val PARAMS = "${ID}/params"
    }
}

interface UnionType : Type {

    val types: TList<Type>
        get() = getField(Fields.TYPES)

    companion object {
        const val ID = "tenshu/core/schema/type/union"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Type.Schema,
            fields = {
                list(Fields.TYPES, of = Type.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            types: List<Type>
        ): UnionType = LazyUnionType(context, types)
    }

    object Fields {
        const val TYPES = "${ID}/types"
    }
}

/**
 * A reference to a [TypeParameterDecl] declared by an [OperationSchema] (or, in a future
 * extension, a [CompositeSchema]). Stored unresolved on the schema; substitution happens
 * lazily at consumer sites that need it (resolver, JS SDK walker, future type checker).
 *
 * Carries only the variable name. Bounds live on the declaring [TypeParameterDecl] in the
 * operation's `typeParams` list.
 */
interface TypeVariable : Type {

    val name: TString
        get() = getField(Fields.NAME)

    companion object {
        const val ID = "tenshu/core/schema/type/variable"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Type.Schema,
            fields = {
                string(Fields.NAME)
            }
        )

        fun create(
            context: TContext? = null,
            name: String,
        ): TypeVariable = LazyTypeVariable(context, name)
    }

    object Fields {
        const val NAME = "${ID}/name"
    }
}

private class LazyGenericType(
    private val context: TContext?,
    override val baseSchema: DataSchema,
    private val initialParams: List<TypeParameter>
) : GenericType {

    override val params: TList<TypeParameter>
        get() = initialParams.toTList(context)

    private val delegate: GenericType by lazy {
        if (context == null) error("LazyGenericType materialization requires a context.")
        context.dataFactory().createComposite(
            type = GenericType.ID,
            fields = listOf(
                baseSchema,
                initialParams.toTList(context)
            )
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            GenericType.Fields.BASE_SCHEMA -> baseSchema as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            GenericType.Fields.BASE_SCHEMA -> baseSchema as T
            else -> delegate.getOptionalField(reference)
        }
    }
}

private class LazyUnionType(
    private val context: TContext?,
    private val initialTypes: List<Type>
) : UnionType {

    override val types: TList<Type>
        get() = initialTypes.toTList(context)

    private val delegate: UnionType by lazy {
        if (context == null) error("LazyUnionType materialization requires a context.")
        context.dataFactory().createComposite(
            type = UnionType.ID,
            fields = listOf(
                initialTypes.toTList(context)
            )
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            UnionType.Fields.TYPES -> types as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            UnionType.Fields.TYPES -> types as T
            else -> delegate.getOptionalField(reference)
        }
    }
}

private class LazyTypeVariable(
    private val context: TContext?,
    nameStr: String,
) : TypeVariable {

    override val name: TString = SimpleTString(nameStr)

    private val delegate: TypeVariable by lazy {
        if (context == null) error("LazyTypeVariable materialization requires a context.")
        context.dataFactory().createComposite(
            type = TypeVariable.ID,
            fields = listOf(name)
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeVariable.Fields.NAME -> name as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            TypeVariable.Fields.NAME -> name as T
            else -> delegate.getOptionalField(reference)
        }
    }
}
