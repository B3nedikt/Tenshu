package org.tenshu.routine.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.routine.Routine

/**
 * Typed RO so chain DSL predicates like `search<Routine> { routineId equalTo X }`
 * see a typed receiver.
 */
internal class RoutineRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), Routine {
    override val schema: CompositeSchema = schema
}
