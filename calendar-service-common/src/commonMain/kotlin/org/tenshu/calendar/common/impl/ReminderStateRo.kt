package org.tenshu.calendar.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.ReminderState

/**
 * Typed RO so chain-DSL predicates like `searchAll<ReminderState> { … }` see a typed
 * receiver rather than a bare `Composite`.
 */
internal class ReminderStateRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), ReminderState {
    override val schema: CompositeSchema = schema
}
