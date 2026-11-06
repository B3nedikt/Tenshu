package org.tenshu.hub.management.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.hub.management.HubCommand

/**
 * Typed RO that implements [HubCommand] so chain DSL predicates like
 * `search<HubCommand> { commandId equalTo X }` see a typed receiver.
 */
internal class HubCommandRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), HubCommand {
    override val schema: CompositeSchema = schema
}
