package org.tenshu.core.trait

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.trait

interface Trait : Composite {

    override val schema: TraitSchema
        get() = Schema

    val traitRequirements: TList<TraitRequirement>
        get() = getField(TRAIT_REQUIREMENTS)

    companion object {
        const val ID = "tenshu/core/trait"
        const val TRAIT_REQUIREMENTS = "traitRequirements"

        val Schema: TraitSchema = trait(
            id = ID,
            parent = TraitSchema.Schema
        )
    }
}

interface TraitSchema : CompositeSchema {

    val traitRequirements: TList<TraitRequirementSchema>
        get() = getField(Trait.TRAIT_REQUIREMENTS)

    companion object {
        const val ID = "tenshu/core/data/schema/trait"

        val Schema: TraitSchema = trait(
            id = ID,
            parent = CompositeSchema.Schema
        )
    }
}
