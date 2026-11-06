package org.tenshu.home.device.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.home.device.DeviceCommand

/**
 * Typed RO that implements [DeviceCommand] so chain DSL predicates like
 * `search<DeviceCommand> { commandId equalTo X }` see a typed receiver.
 */
internal class DeviceCommandRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), DeviceCommand {
    override val schema: CompositeSchema = schema
}
