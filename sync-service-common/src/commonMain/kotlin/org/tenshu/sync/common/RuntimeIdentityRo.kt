package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.RuntimeIdentity

/**
 * Typed RO for [RuntimeIdentity] (`core-schema-skew-surface.md` SK-2), mirroring
 * [SchemaFenceStatusRo].
 *
 * ⚠⚠ Registering the composite is what makes the row *readable*, and its absence is invisible at
 * compile time. The proxy would publish the row, the relation would exist, and every client select
 * projecting it would die as an opaque `error/internal` (§6.10). The schema-shape baseline does not
 * catch it either: an unregistered composite is not in the walked service graph at all, so it is
 * neither pinned nor serialisable, and regenerating the baseline reports no change.
 */
internal class RuntimeIdentityRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), RuntimeIdentity {
    override val schema: CompositeSchema = schema
}
