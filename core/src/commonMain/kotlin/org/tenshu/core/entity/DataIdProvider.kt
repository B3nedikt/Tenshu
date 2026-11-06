package org.tenshu.core.entity

import org.tenshu.core.Data
import org.tenshu.core.schema.trait
import org.tenshu.core.schema.valueTraitRequirement
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema
import org.tenshu.core.trait.ValueTraitRequirementSchema

interface DataIdProvider<T : Data> : Trait {

    override val schema: DataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/data/id/Provider"

        val DATA_ID_REQUIREMENT: ValueTraitRequirementSchema =
            valueTraitRequirement(Requirements.DATA_ID)

        val Schema: DataIdProviderSchema = trait(
            id = ID,
            traitRequirements = listOf(DATA_ID_REQUIREMENT)
        )
    }

    object Requirements {
        const val DATA_ID = "$ID/dataId"
    }
}

interface DataIdProviderSchema : TraitSchema
