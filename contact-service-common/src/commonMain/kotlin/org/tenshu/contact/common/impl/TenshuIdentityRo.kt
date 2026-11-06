package org.tenshu.contact.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.TenshuIdentity

/**
 * Typed RO that implements [TenshuIdentity] so chain DSL predicates see a typed
 * receiver.
 */
internal class TenshuIdentityRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), TenshuIdentity {
    override val schema: CompositeSchema = schema
}
