package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SchemaFenceStatus

/**
 * Typed RO that implements [SchemaFenceStatus] so chain DSL predicates like
 * `search<SchemaFenceStatus> { key equalTo SINGLETON_KEY }` see a typed receiver.
 *
 * ⚠ Registered in the **common** module (`syncServiceCommonModule`), which both the
 * proxy and the client load (§6.10): a composite reachable through a client-side chain
 * needs its `roFactory` where the client resolves it, or every select projecting it dies
 * as an opaque `error/internal`.
 */
internal class SchemaFenceStatusRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), SchemaFenceStatus {
    override val schema: CompositeSchema = schema
}
