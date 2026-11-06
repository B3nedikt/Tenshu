package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.sync.ScheduleItem

/**
 * Keys a [ScheduleItem] by its `dedupKey` (mirror of
 * [SyncBlockDataIdProviderImpl]). Stable identity makes dedup a
 * remove-then-add and cancel-by-key a single `removeFromArray` (§3.3).
 */
class ScheduleItemDataIdProviderImpl(
    context: TContext
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<ScheduleItem>>(
                    type = GetStringDataId.ID,
                    fields = listOf(ScheduleItem.Fields.DEDUP_KEY.toTString(context))
                )
            )
        )
    )
), DataIdProvider<ScheduleItem>
