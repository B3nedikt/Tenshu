package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.traitRequirementSchema

interface TraitRequirement : Operation {

    override val schema: TraitRequirementSchema

    companion object {
        const val ID = "tenshu/core/traitRequirement"

        val Schema: TraitRequirementSchema = traitRequirementSchema(
            id = ID,
        )
    }
}

interface TraitRequirementSchema : OperationSchema {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/traitRequirementSchema"

        val Schema: TraitRequirementSchema = traitRequirementSchema(
            id = ID,
            parent = OperationSchema.Schema
        )
    }
}
