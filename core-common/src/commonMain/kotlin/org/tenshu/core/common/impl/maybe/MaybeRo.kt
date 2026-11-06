package org.tenshu.core.common.impl.maybe

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.toType

/**
 * Phase 5.1 of `docs/adr/0009-operation-schema-typing.md`: [elementType] is the construction-time
 * element schema, derived by the registered factory from the requested `Type.params`.
 *
 * Phase 3.3 of `docs/adr/0005-composite-schema-typing.md`: [elementType] is also forwarded to the
 * [CompositeRo] super as a single-element `params` list so field-access ROs (`Maybe.Some.value`,
 * declared as `typeVar("E")` after Phase 2.5) substitute correctly.
 */
internal class MaybeRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
    val elementType: DataSchema = Data.Schema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
    params = listOf(
        TypeParameter.create(
            type = elementType.toType(),
            variance = Variance.Covariant
        )
    )
), Maybe<T>
