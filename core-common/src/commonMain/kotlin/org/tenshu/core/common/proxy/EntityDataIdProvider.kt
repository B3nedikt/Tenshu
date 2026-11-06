package org.tenshu.core.common.proxy

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.EntityDataIdProvider
import org.tenshu.core.entity.ResolveEntityDataId
import org.tenshu.core.trait.TraitRequirement

internal class EntityDataIdProviderImpl(
    context: TContext,
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<ResolveEntityDataId>(
                    type = ResolveEntityDataId.ID,
                    fields = emptyList()
                )
            )
        )
    )
), EntityDataIdProvider
