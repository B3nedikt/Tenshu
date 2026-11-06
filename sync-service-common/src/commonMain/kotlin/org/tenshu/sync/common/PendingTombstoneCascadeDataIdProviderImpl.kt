package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.TraitRequirement

/** Keys a [PendingTombstoneCascade] by its tombstone's `blockId`, so a re-record replaces the row. */
internal class PendingTombstoneCascadeDataIdProviderImpl(
    context: TContext
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<*>>(
                    type = GetStringDataId.ID,
                    fields = listOf(PendingTombstoneCascade.Fields.BLOCK_ID.toTString(context))
                )
            )
        )
    )
), DataIdProvider<PendingTombstoneCascade>
