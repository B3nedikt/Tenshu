package org.tenshu.file.common.impl.sync

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.CompositeSchema

/**
 * Typed RO that implements [FileSyncSetting] so chain DSL predicates like
 * `fileSyncSettings.search<FileSyncSetting> { deviceId equalTo X }` see a typed
 * receiver (mirrors `DeviceSettingRo`). Without it the `.search<T>` predicate path
 * has no request-object factory for the element type and fails at read time.
 */
internal class FileSyncSettingRo(
    requestChain: RequestChain,
    context: TContext,
    schema: CompositeSchema,
) : CompositeRo(
    requestChain = requestChain,
    context = context,
    schema = schema,
), FileSyncSetting {
    override val schema: CompositeSchema = schema
}
