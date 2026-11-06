package org.tenshu.home.device.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.DeviceState

/**
 * Typed RO that implements [DeviceState] so chain DSL predicates like
 * `search<DeviceState> { deviceId equalTo X }` see a typed receiver.
 */
internal class DeviceStateRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), DeviceState {
    override val schema: CompositeSchema = schema
}
