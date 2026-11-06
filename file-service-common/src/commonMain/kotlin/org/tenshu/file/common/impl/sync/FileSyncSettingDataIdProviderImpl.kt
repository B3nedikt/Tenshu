package org.tenshu.file.common.impl.sync

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.sync.FileSyncSettingDataIdProvider
import org.tenshu.core.trait.TraitRequirement

/**
 * Mirrors `ShareDataIdProviderImpl`. Keys
 * [org.tenshu.core.file.sync.FileSyncSetting] projections by their `deviceId`
 * field (one row per device) so the proxy-side maintainer's
 * `saveData(setting)` computes the right `StringDataId` without manual
 * construction.
 */
internal class FileSyncSettingDataIdProviderImpl(
    context: TContext,
    override val schema: StringDataIdProviderSchema,
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<*>>(
                    type = GetStringDataId.ID,
                    fields = listOf(schema.fieldRef)
                )
            )
        ),
        schema.fieldRef
    )
), FileSyncSettingDataIdProvider
