package org.tenshu.preference.common.impl

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.preference.RealmSetting

/**
 * Typed RO that implements [RealmSetting] so chain DSL predicates like
 * `search<RealmSetting> { preferenceId equalTo X }` see a typed receiver.
 */
internal class RealmSettingRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), RealmSetting {
    override val schema: CompositeSchema = schema
}
