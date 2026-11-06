package org.tenshu.core.schema

import org.tenshu.core.collection.list.TList
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema

interface CompositeSchema : DataSchema {

    override val schema: CompositeSchema
        get() = Schema

    val values: TList<Field>
        get() = getField(Fields.VALUES)

    val traits: TList<TraitSchema>
        get() = getField(Fields.TRAITS)

    /**
     * Declared type parameters for parameterised composites (`TList<E>`, `Maybe<T>`,
     * `TPair<A, B>`, `TMap<K, V>`, …). Empty when the composite isn't generic. Field
     * declarations inside the composite can reference these via [TypeVariable]
     * (`typeVar("E")` / `listType(decl)` / `maybeType(decl)`).
     *
     * Phase 1 of `docs/adr/0005-composite-schema-typing.md`. Sibling to
     * [OperationSchema.typeParams] from `docs/adr/0009-operation-schema-typing.md`; operations
     * inherit this slot via their parent chain.
     */
    val typeParams: TList<TypeParameterDecl>
        get() = getField(Fields.TYPE_PARAMS)

    companion object {
        const val ID = "tenshu/core/data/schema/composite"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = DataSchema.Schema,
            fields = {
                list(Fields.VALUES)
                list(Fields.TRAITS, of = Trait.Schema)
                list(Fields.TYPE_PARAMS, of = TypeParameterDecl.Schema)
            }
        )
    }

    object Fields {
        const val VALUES = "${ID}/values"
        const val TRAITS = "${ID}/traits"
        const val TYPE_PARAMS = "${ID}/typeParams"
    }
}
