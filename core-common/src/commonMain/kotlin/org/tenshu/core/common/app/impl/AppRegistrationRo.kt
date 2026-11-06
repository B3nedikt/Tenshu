package org.tenshu.core.common.app.impl

import org.tenshu.core.app.AppRegistration
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema

/**
 * Typed RO so chain DSL predicates like
 * `search<AppRegistration> { appId equalTo X }` see a typed receiver.
 */
internal class AppRegistrationRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), AppRegistration {
    override val schema: CompositeSchema = schema
}
