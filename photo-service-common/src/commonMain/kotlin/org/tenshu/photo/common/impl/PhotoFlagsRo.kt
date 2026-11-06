package org.tenshu.photo.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.photo.PhotoFlags

/**
 * Typed RO so chain-DSL predicates like `searchAll<PhotoFlags> { … }` see a typed receiver rather
 * than a bare `Composite`.
 *
 * ⚠ Easy to forget, and nothing fails until a chain projects the type — at which point the read
 * dies as an opaque `error/internal` naming nothing (the `GetWorkoutStreamsRo` lesson).
 */
internal class PhotoFlagsRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), PhotoFlags {
    override val schema: CompositeSchema = schema
}
