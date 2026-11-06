package org.tenshu.contact.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.ContactSource

/**
 * Typed RO that implements [ContactSource] so chain DSL predicates see a typed receiver.
 */
internal class ContactSourceRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), ContactSource {
    override val schema: CompositeSchema = schema
}
