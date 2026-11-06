package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncCatchUpStatus

/**
 * Typed RO that implements [SyncCatchUpStatus] so chain DSL predicates like
 * `search<SyncCatchUpStatus> { key equalTo writerId }` see a typed receiver.
 * Mirrors `SchemaFenceStatusRo`.
 *
 * ⚠ Registered in the **common** module (`syncServiceCommonModule`), which both the
 * proxy and the client load (§6.10): a composite reachable through a client-side chain
 * needs its `roFactory` where the client resolves it, or every select projecting it dies
 * as an opaque `error/internal`.
 */
internal class SyncCatchUpStatusRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), SyncCatchUpStatus {
    override val schema: CompositeSchema = schema
}
