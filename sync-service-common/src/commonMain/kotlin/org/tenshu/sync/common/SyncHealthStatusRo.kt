package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncHealthStatus

/**
 * Typed RO that implements [SyncHealthStatus] so chain DSL predicates like
 * `search<SyncHealthStatus> { key equalTo SINGLETON_KEY }` see a typed receiver.
 */
internal class SyncHealthStatusRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), SyncHealthStatus {
    override val schema: CompositeSchema = schema
}
