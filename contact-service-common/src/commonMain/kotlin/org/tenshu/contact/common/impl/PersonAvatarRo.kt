package org.tenshu.contact.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.PersonAvatar

/**
 * Typed RO that implements [PersonAvatar] so chain DSL predicates see a typed receiver.
 */
internal class PersonAvatarRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), PersonAvatar {
    override val schema: CompositeSchema = schema
}
