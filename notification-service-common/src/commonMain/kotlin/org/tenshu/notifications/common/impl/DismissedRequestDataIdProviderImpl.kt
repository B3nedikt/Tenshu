package org.tenshu.notifications.common.impl

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.notifications.DismissedRequestDataIdProvider
import org.tenshu.core.trait.TraitRequirement

internal class DismissedRequestDataIdProviderImpl(
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
), DismissedRequestDataIdProvider
