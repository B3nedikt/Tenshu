package org.tenshu.storage.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.StorageStatus

/**
 * Typed RO that implements [StorageStatus] so chain DSL predicates like
 * `search<StorageStatus> { key equalTo SINGLETON_KEY }` see a typed receiver.
 */
internal class StorageStatusRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), StorageStatus {
    override val schema: CompositeSchema = schema
}
