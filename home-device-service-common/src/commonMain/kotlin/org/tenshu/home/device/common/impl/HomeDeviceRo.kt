package org.tenshu.home.device.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.HomeDevice

/**
 * Typed RO that implements [HomeDevice] so chain DSL predicates like
 * `search<HomeDevice> { deviceId equalTo X }` see a typed receiver.
 */
internal class HomeDeviceRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), HomeDevice {
    override val schema: CompositeSchema = schema
}
