package org.tenshu.file.common.impl.sync

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.sync.PathSyncOverrideEntryDataIdProvider
import org.tenshu.core.trait.TraitRequirement

/**
 * Mirrors `TripMemberDataIdProviderImpl`. Keys
 * [org.tenshu.core.file.sync.PathSyncOverrideEntry] by its `pathPrefix` field so a
 * re-set of the same prefix folds to one row (the sparse override invariant) and
 * the list serialiser can address each element.
 */
internal class PathSyncOverrideEntryDataIdProviderImpl(
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
), PathSyncOverrideEntryDataIdProvider
