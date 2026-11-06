package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncCatchUpHeartbeat

/**
 * Typed RO for [SyncCatchUpHeartbeat]; mirrors [SyncCatchUpStatusRo] and, like it, is registered in
 * the **common** module so a client-side chain projecting the row resolves it.
 */
internal class SyncCatchUpHeartbeatRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), SyncCatchUpHeartbeat {
    override val schema: CompositeSchema = schema
}
