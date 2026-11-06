package org.tenshu.file.common.impl.sync

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.sync.PathSyncOverrideSetDataIdProvider
import org.tenshu.core.trait.TraitRequirement

/**
 * Mirrors `FileSyncSettingDataIdProviderImpl`. Content-addresses a
 * [org.tenshu.core.file.sync.PathSyncOverrideSet] by its derived `key` field so a
 * composite carrying a list field is addressable and identical override sets fold
 * to one entry.
 */
internal class PathSyncOverrideSetDataIdProviderImpl(
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
), PathSyncOverrideSetDataIdProvider
