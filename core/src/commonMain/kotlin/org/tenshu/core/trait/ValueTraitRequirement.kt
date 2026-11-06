package org.tenshu.core.trait

import org.tenshu.core.Data
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.valueTraitRequirementSchema

interface ValueTraitRequirement<R : Data, T : Data> : TraitRequirement {

    override val schema: ValueTraitRequirementSchema

    companion object {
        const val ID = "tenshu/core/valueTraitRequirement"

        val Schema: ValueTraitRequirementSchema = valueTraitRequirementSchema(
            id = ID,
            parent = TraitRequirement.Schema
        )
    }
}

interface ValueTraitRequirementSchema : TraitRequirementSchema {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/valueTraitRequirementSchema"

        val Schema: ValueTraitRequirementSchema = valueTraitRequirementSchema(
            id = ID,
            parent = TraitRequirementSchema.Schema
        )
    }
}
