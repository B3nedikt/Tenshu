package org.tenshu.core.common.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.substitute
import org.tenshu.core.schema.toType

open class CompositeRo(
    override val requestChain: RequestChain,
    override val context: TContext,
    override val schema: CompositeSchema,
    /**
     * Construction-time parameterisation of [schema]. When the receiver was created from
     * a parameterised request (e.g. `Maybe<File>` resolves to a `MaybeRo` whose schema is
     * `Maybe.Some.Schema` with `typeParams = [E]` and one bound type `File`), [params]
     * carries those bindings so [resolveField] can substitute typeVar field declarations
     * (Phase 2.5) into concrete types before delegating to the RO registry.
     *
     * Phase 3.3 of `docs/adr/0005-composite-schema-typing.md`.
     */
    private val params: List<TypeParameter> = emptyList(),
) : Composite, TContext, RequestObject {

    override val components: Map<String, TComponent>
        get() = context.components

    override fun <T : Data> getOptionalField(reference: String): T? {
        val field = schema.values.toList().find { it.id.get() == reference }
            ?: return null

        if (!field.isOptional.get()) {
            error("Field $reference is mandatory in ${schema.id.get()}. Use getField() instead.")
        }

        return resolveField(reference, field)
    }

    override fun <T : Data> getField(reference: String): T {
        val field = schema.values.toList().find { it.id.get() == reference }
            ?: error("Unknown field $reference of ${schema.id.get()}")

        if (field.isOptional.get()) {
            error("Field $reference is optional in ${schema.id.get()}. Use getOptionalField() instead.")
        }

        return resolveField<T>(reference, field)
            ?: error("Mandatory field $reference of ${schema.id.get()} could not be resolved.")
    }

    private fun <T : Data> resolveField(reference: String, field: Field): T? {
        // Phase 3.3 of `docs/adr/0005-composite-schema-typing.md`: substitute typeVar references in
        // field types using the receiver's parameterisation (`schema.typeParams` zipped
        // against the construction-time `params`). Without this, parameterised composites
        // whose fields carry typeVars after Phase 2.5 (e.g. `Maybe.Some.value: typeVar("E")`)
        // would hit the RO factory with a `TypeVariable` and fail. `defaultForUnbound = Data`
        // matches the wire-layer fallback when no parameterisation is available.
        val typeParamDecls = schema.typeParams.toList()
        val bindings: Map<String, Type> =
            if (typeParamDecls.isNotEmpty() && params.isNotEmpty()) {
                typeParamDecls.zip(params)
                    .associate { (decl, param) -> decl.name.get() to param.type }
            } else {
                emptyMap()
            }
        val resolvedType = substitute(
            type = field.type,
            bindings = bindings,
            defaultForUnbound = Data.Schema.toType(),
        )

        val ro = context.roProvider().createRequestObject<Data>(
            type = resolvedType,
            requestChain = requestChain.addReference(reference),
            context = context
        )
        @Suppress("UNCHECKED_CAST")
        return ro as? T
    }

}
