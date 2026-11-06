package org.tenshu.hub.management.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.hub.management.HubStatus

/**
 * Typed RO that implements [HubStatus] so chain DSL predicates like
 * `search<HubStatus> { deviceId equalTo X }` see a typed receiver.
 */
internal class HubStatusRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), HubStatus {
    override val schema: CompositeSchema = schema
}
