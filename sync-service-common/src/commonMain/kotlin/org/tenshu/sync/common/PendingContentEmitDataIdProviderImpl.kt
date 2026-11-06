package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.TraitRequirement

/**
 * Keys a [PendingContentEmit] on its [PendingContentEmit.Fields.EMIT_KEY], so an entry can be
 * addressed (and replaced) by `(entity, property)` rather than by position — what the journal's
 * remove-then-add last-wins write and its clear-on-success both rely on.
 */
internal class PendingContentEmitDataIdProviderImpl(
    context: TContext
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<*>>(
                    type = GetStringDataId.ID,
                    fields = listOf(PendingContentEmit.Fields.EMIT_KEY.toTString(context))
                )
            )
        )
    )
), DataIdProvider<PendingContentEmit>
