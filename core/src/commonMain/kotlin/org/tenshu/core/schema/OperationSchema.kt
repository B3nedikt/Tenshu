package org.tenshu.core.schema

import org.tenshu.core.collection.list.TList

interface OperationSchema : CompositeSchema {

    override val schema: CompositeSchema
        get() = Schema

    /**
     * The [Type] this operation dispatches on, or `null` for unattached operations (e.g.
     * top-level service operations with no implicit caller). Single-valued — multi-arg
     * dispatch belongs in [params].
     */
    val receiver: Type?
        get() = getOptionalField(Fields.RECEIVER)

    /**
     * Declared type parameters available for use in [receiver], [params], and [returnType]
     * via [TypeVariable] references. Empty when the operation declares no type variables.
     *
     * Overrides [CompositeSchema.typeParams]. The two backing fields (this one keyed by
     * `OperationSchema.Fields.TYPE_PARAMS`, the parent keyed by
     * `CompositeSchema.Fields.TYPE_PARAMS`) coexist during the
     * `docs/adr/0005-composite-schema-typing.md` migration window — operations don't yet use the
     * composite-level slot. Phase 2 of that proposal (or a later phase) collapses the
     * two onto one source.
     */
    override val typeParams: TList<TypeParameterDecl>
        get() = getField(Fields.TYPE_PARAMS)

    val params: TList<Field>
        get() = getField(Fields.PARAMS)

    override val values: TList<Field>
        get() = params

    val returnType: Type
        get() = getField(Fields.RETURN_TYPE)

    companion object {
        const val ID = "tenshu/core/data/schema/operation"

        val Schema: OperationSchema = operation(
            id = ID,
            parent = CompositeSchema.Schema,
            params = {
                list(Fields.PARAMS)
                reference(Fields.RETURN_TYPE, type = DataSchema.Schema)
                field(Fields.RECEIVER, Type.Schema.toType(), isOptional = true)
                list(Fields.TYPE_PARAMS, of = TypeParameterDecl.Schema)
            }
        )
    }

    object Fields {
        const val PARAMS = "${ID}/params"
        const val RETURN_TYPE = "${ID}/returnType"
        const val RECEIVER = "${ID}/receiver"
        const val TYPE_PARAMS = "${ID}/typeParams"
    }
}
