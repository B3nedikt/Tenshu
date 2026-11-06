package org.tenshu.file.common.impl.share

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.share.ShareDataIdProvider
import org.tenshu.core.trait.TraitRequirement

/**
 * Mirrors `FileDataIdProviderImpl`. Keys [org.tenshu.core.file.share.Share]
 * entities by the `shareId` field (the relay-minted opaque id) so
 * `ExecutionContext.saveData(share)` computes the right `StringDataId`
 * without manual construction at every call site.
 */
internal class ShareDataIdProviderImpl(
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
), ShareDataIdProvider
