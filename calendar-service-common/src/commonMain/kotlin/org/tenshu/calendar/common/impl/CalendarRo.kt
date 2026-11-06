package org.tenshu.calendar.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.Calendar

/**
 * Typed RO so chain-DSL predicates like `searchAll<Calendar> { … }` see a typed
 * receiver rather than a bare `Composite`.
 */
internal class CalendarRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), Calendar {
    override val schema: CompositeSchema = schema
}
