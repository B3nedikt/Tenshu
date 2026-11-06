package org.tenshu.contact.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.ContactInvite

/**
 * Typed RO that implements [ContactInvite] so chain DSL predicates see a typed
 * receiver.
 */
internal class ContactInviteRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), ContactInvite {
    override val schema: CompositeSchema = schema
}
