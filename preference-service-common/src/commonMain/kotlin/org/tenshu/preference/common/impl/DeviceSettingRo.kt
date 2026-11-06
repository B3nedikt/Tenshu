package org.tenshu.preference.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.preference.DeviceSetting

/**
 * Typed RO that implements [DeviceSetting] so chain DSL predicates like
 * `search<DeviceSetting> { compositeKey equalTo X }` see a typed receiver.
 */
internal class DeviceSettingRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), DeviceSetting {
    override val schema: CompositeSchema = schema
}
