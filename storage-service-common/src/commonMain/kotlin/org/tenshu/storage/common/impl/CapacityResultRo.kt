package org.tenshu.storage.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.CapacityResult

/**
 * Typed RO so the [CheckCapacity][org.tenshu.storage.CheckCapacity] op return
 * materialises as a typed [CapacityResult] on the client.
 */
internal class CapacityResultRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), CapacityResult {
    override val schema: CompositeSchema = schema
}
