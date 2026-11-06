package org.tenshu.photo.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.photo.TrashedPhoto

/** Typed RO so a chain predicate sees a typed receiver — the `GetWorkoutStreamsRo` lesson. */
internal class TrashedPhotoRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), TrashedPhoto {
    override val schema: CompositeSchema = schema
}
