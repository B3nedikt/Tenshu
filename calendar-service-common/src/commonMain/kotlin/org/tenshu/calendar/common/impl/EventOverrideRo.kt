package org.tenshu.calendar.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.EventOverride

/**
 * Typed RO so chain-DSL predicates like `searchAll<EventOverride> { … }` see a typed
 * receiver rather than a bare `Composite`.
 */
internal class EventOverrideRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), EventOverride {
    override val schema: CompositeSchema = schema
}
