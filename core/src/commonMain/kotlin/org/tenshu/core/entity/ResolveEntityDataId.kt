package org.tenshu.core.entity

import org.tenshu.core.Data
import org.tenshu.core.schema.valueTraitRequirementSchema
import org.tenshu.core.trait.PureOperation
import org.tenshu.core.trait.ValueTraitRequirement
import org.tenshu.core.trait.ValueTraitRequirementSchema

interface ResolveEntityDataId : ValueTraitRequirement<Entity<Data>, DataId.EntityDataId> {

    override val schema: ValueTraitRequirementSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/entity/resolveDataId"

        val Schema: ValueTraitRequirementSchema = valueTraitRequirementSchema(
            id = ID,
            parent = DataIdProvider.DATA_ID_REQUIREMENT,
            traits = listOf(PureOperation.Schema)
        )
    }
}
