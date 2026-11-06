package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.CompositeTypeReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.substitute
import org.tenshu.core.schema.toType

open class EntityRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
    override val schema: CompositeSchema,
    private val params: List<TypeParameter> = emptyList(),
) : CompositeRo(requestChain, context, schema, params), Entity<T> {

    override val properties: Map<PropertyTypeReference, Data>
        get() = error("Use getProperty() instead on an RO")

    override val type: CompositeTypeReference
        get() = error("Use schema.id instead on an RO")

    override val fields: Map<FieldReference, Data>
        get() = error("Use getFieldValue() instead on an RO")

    override fun <T : Data> getProperty(reference: String): T? {
        val schema = context.schemaProvider().getSchema(reference)
            ?: error("No schema registered for property reference: $reference")
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<T>(
            type = schema.toType(),
            requestChain = requestChain.addReference(reference),
            context = context
        )
    }

    override fun <T> getFieldValue(reference: String): T =
        requestObjectFor(reference)
            ?: error("Unknown field $reference of ${schema.id.get()}")

    /**
     * ⚠⚠ **On an Ro, "optional" cannot mean "the value is absent" — there is no value yet.** This
     * side builds the *request*, so the only absence it can observe is a field the schema does not
     * declare. An optional field still has to yield a request object or the chain referencing it
     * could not be built at all; the nullability that matters is on the read side
     * (`EntityDto.getOptionalFieldValue`), where a projected row either carries the field or does
     * not.
     */
    override fun <T> getOptionalFieldValue(reference: String): T? = requestObjectFor(reference)

    private fun <T> requestObjectFor(reference: String): T? {
        val field = schema.values.toList().find { it.id.get() == reference }
            ?: return null

        // Phase 3.3 of `docs/adr/0005-composite-schema-typing.md`: substitute typeVar references in
        // field types using the entity's parameterisation. Mirrors `CompositeRo.resolveField`
        // — the entity-side reuse path now consumes the same substitution machinery as
        // composite-side field access, so post-Phase-2.5 typeVar field declarations don't
        // leak through into the RO factory.
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

        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<Data>(
            type = resolvedType,
            requestChain = requestChain.addReference(reference),
            context = context
        ) as T
    }
}
