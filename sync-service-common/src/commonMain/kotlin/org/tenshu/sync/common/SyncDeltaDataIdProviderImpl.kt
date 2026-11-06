package org.tenshu.sync.common

import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.sync.SyncDelta

/**
 * Identity for [SyncDelta] keyed on [SyncDelta.deltaId] (mirror of
 * [SyncBlockDataIdProviderImpl]) — needed so a staged delta is addressable in
 * the durable lane journal (task-scoped sync lane §10.3 D6): journal-array
 * removal is by element `DataId`.
 */
class SyncDeltaDataIdProviderImpl(
    context: TContext
) : CompositeDto(
    listOf(
        context.dataFactory().createList<TraitRequirement>(
            listOf(
                context.dataFactory().createComposite<GetStringDataId<SyncDelta>>(
                    type = GetStringDataId.ID,
                    fields = listOf(SyncDelta.Fields.DELTA_ID.toTString(context))
                )
            )
        )
    )
), DataIdProvider<SyncDelta>
