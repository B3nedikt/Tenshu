package org.tenshu.core.entity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.schema.trait
import org.tenshu.core.schema.valueTraitRequirement

interface CompositeDataIdProvider<T : Composite> : DataIdProvider<T> {

    override val schema: CompositeDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/composite/dataIdProvider"

        val Schema: CompositeDataIdProviderSchema = trait(
            id = ID,
            parent = DataIdProvider.Schema,
            traitRequirements = listOf(
                valueTraitRequirement(DataIdProvider.Requirements.DATA_ID)
            )
        )
    }
}

interface CompositeDataIdProviderSchema : DataIdProviderSchema

interface StringDataIdProviderSchema : CompositeDataIdProviderSchema {
    val fieldRef: TString

    object Fields {
        const val FIELD_REF = "${CompositeDataIdProvider.ID}/fieldRef"
    }
}
