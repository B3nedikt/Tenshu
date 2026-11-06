package org.tenshu.core.entity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.schema.string
import org.tenshu.core.schema.valueTraitRequirementSchema
import org.tenshu.core.trait.PureOperation
import org.tenshu.core.trait.ValueTraitRequirement
import org.tenshu.core.trait.ValueTraitRequirementSchema

interface GetStringDataId<T : Composite> : ValueTraitRequirement<Entity<T>, DataId.StringDataId> {

    override val schema: ValueTraitRequirementSchema
        get() = Schema

    val fieldRef: TString
        get() = getField(Fields.FIELD_REF)

    companion object {
        const val ID = "tenshu/core/entity/getStringDataId"

        val Schema: ValueTraitRequirementSchema = valueTraitRequirementSchema(
            id = ID,
            parent = DataIdProvider.DATA_ID_REQUIREMENT,
            params = {
                string(Fields.FIELD_REF)
            },
            traits = listOf(PureOperation.Schema)
        )
    }

    object Fields {
        const val FIELD_REF = "$ID/fieldRef"
    }
}
