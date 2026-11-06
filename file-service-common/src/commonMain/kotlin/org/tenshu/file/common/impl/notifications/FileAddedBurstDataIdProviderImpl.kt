package org.tenshu.file.common.impl.notifications

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.notifications.FileAddedBurstDataIdProvider
import org.tenshu.core.trait.TraitRequirement

/** Mirrors `NotificationDataIdProviderImpl` — the materialised `stringDataId`
 *  trait keying [org.tenshu.core.file.notifications.FileAddedBurst] by its
 *  constant `key` field. */
internal class FileAddedBurstDataIdProviderImpl(
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
), FileAddedBurstDataIdProvider
