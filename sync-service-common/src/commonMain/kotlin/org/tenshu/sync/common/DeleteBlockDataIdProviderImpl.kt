package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.sync.DeleteBlock

class DeleteBlockDataIdProviderImpl(
    context: TContext
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<DeleteBlock>>(
                    type = GetStringDataId.ID,
                    fields = listOf(DeleteBlock.Fields.BLOCK_ID.toTString(context))
                )
            )
        )
    )
), DataIdProvider<DeleteBlock>
